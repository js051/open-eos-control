package dev.openeos.control.data

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

object PtpDevicePropertyCode {
    const val BATTERY_LEVEL = 0x5001
    const val FUNCTIONAL_MODE = 0x5002
    const val IMAGE_SIZE = 0x5003
    const val COMPRESSION_SETTING = 0x5004
    const val WHITE_BALANCE = 0x5005
    const val RGB_GAIN = 0x5006
    const val F_NUMBER = 0x5007
    const val FOCAL_LENGTH = 0x5008
    const val FOCUS_DISTANCE = 0x5009
    const val FOCUS_MODE = 0x500A
    const val EXPOSURE_METERING_MODE = 0x500B
    const val FLASH_MODE = 0x500C
    const val EXPOSURE_TIME = 0x500D
    const val EXPOSURE_PROGRAM_MODE = 0x500E
    const val EXPOSURE_INDEX = 0x500F
    const val EXPOSURE_BIAS_COMPENSATION = 0x5010
    const val DATE_TIME = 0x5011
    const val CAPTURE_DELAY = 0x5012
    const val STILL_CAPTURE_MODE = 0x5013
    const val CONTRAST = 0x5014
    const val SHARPNESS = 0x5015
    const val DIGITAL_ZOOM = 0x5016
    const val EFFECT_MODE = 0x5017
    const val BURST_NUMBER = 0x5018
    const val BURST_INTERVAL = 0x5019
    const val TIMELAPSE_NUMBER = 0x501A
    const val TIMELAPSE_INTERVAL = 0x501B
    const val FOCUS_METERING_MODE = 0x501C
    const val UPLOAD_URL = 0x501D
    const val ARTIST = 0x501E
    const val COPYRIGHT_INFO = 0x501F
}

object MtpObjectPropertyCode {
    const val RATING = 0xDC8A
}

data class PtpDataType(val code: Int) {
    val isArray: Boolean
        get() = code != STRING && code and ARRAY_MASK != 0

    val elementCode: Int
        get() = if (isArray) code and ARRAY_MASK.inv() else code

    val isString: Boolean
        get() = code == STRING

    init {
        require(code == STRING || elementCode in INT8..UINT128) {
            "Unsupported PTP property data type 0x${code.toString(16).uppercase().padStart(4, '0')}."
        }
    }

    companion object {
        const val INT8 = 0x0001
        const val UINT8 = 0x0002
        const val INT16 = 0x0003
        const val UINT16 = 0x0004
        const val INT32 = 0x0005
        const val UINT32 = 0x0006
        const val INT64 = 0x0007
        const val UINT64 = 0x0008
        const val INT128 = 0x0009
        const val UINT128 = 0x000A
        const val ARRAY_MASK = 0x4000
        const val STRING = 0xFFFF
    }
}

sealed interface PtpPropertyValue {
    data class Signed(val value: Long) : PtpPropertyValue
    data class Unsigned(val value: ULong) : PtpPropertyValue
    data class Text(val value: String) : PtpPropertyValue
    data class Raw128(val bytes: ByteArray, val signed: Boolean) : PtpPropertyValue
    data class Sequence(val values: List<PtpPropertyValue>) : PtpPropertyValue
}

sealed interface PtpPropertyForm {
    data object None : PtpPropertyForm

    data class Range(
        val minimum: PtpPropertyValue,
        val maximum: PtpPropertyValue,
        val step: PtpPropertyValue,
    ) : PtpPropertyForm

    data class Enumeration(val values: List<PtpPropertyValue>) : PtpPropertyForm
}

data class PtpDevicePropertyDescriptor(
    val code: Int,
    val dataType: PtpDataType,
    val writable: Boolean,
    val defaultValue: PtpPropertyValue,
    val currentValue: PtpPropertyValue,
    val form: PtpPropertyForm,
)

data class MtpObjectPropertyDescriptor(
    val code: Int,
    val dataType: PtpDataType,
    val writable: Boolean,
    val defaultValue: PtpPropertyValue,
    val groupCode: Long,
    val form: PtpPropertyForm,
)

object MtpObjectPropertyCodec {
    fun decodeSupportedProperties(bytes: ByteArray): Set<Int> {
        val reader = PropertyReader(bytes)
        val count = reader.u32()
        if (count > MAX_OBJECT_PROPERTY_CODES.toULong()) {
            throw PtpProtocolException(
                "MTP object-property list declares $count values; limit is $MAX_OBJECT_PROPERTY_CODES."
            )
        }
        return List(count.toInt()) { reader.u16() }
            .also { reader.requireEnd("MTP object-property list") }
            .toSet()
    }

    fun decodeDescriptor(bytes: ByteArray): MtpObjectPropertyDescriptor {
        val reader = PropertyReader(bytes)
        val propertyCode = reader.u16()
        val dataType = PtpDataType(reader.u16())
        val writable = when (val getSet = reader.u8()) {
            0 -> false
            1 -> true
            else -> throw PtpProtocolException("Invalid MTP ObjectPropDesc GetSet value $getSet.")
        }
        val defaultValue = reader.value(dataType)
        val groupCode = reader.u32().toLong()
        val form = when (val formFlag = reader.u8()) {
            FORM_NONE -> PtpPropertyForm.None
            FORM_RANGE -> PtpPropertyForm.Range(
                minimum = reader.value(dataType),
                maximum = reader.value(dataType),
                step = reader.value(dataType),
            )

            FORM_ENUMERATION -> {
                val count = reader.u16()
                PtpPropertyForm.Enumeration(List(count) { reader.value(dataType) })
            }

            else -> throw PtpProtocolException(
                "Unsupported MTP ObjectPropDesc form 0x${formFlag.toString(16).uppercase()} " +
                    "for property 0x${propertyCode.toString(16).uppercase()}."
            )
        }
        reader.requireEnd("MTP ObjectPropDesc 0x${propertyCode.toString(16).uppercase()}")
        return MtpObjectPropertyDescriptor(
            code = propertyCode,
            dataType = dataType,
            writable = writable,
            defaultValue = defaultValue,
            groupCode = groupCode,
            form = form,
        )
    }

    fun decodeValue(dataType: PtpDataType, bytes: ByteArray): PtpPropertyValue {
        val reader = PropertyReader(bytes)
        return reader.value(dataType).also { reader.requireEnd("MTP object-property value") }
    }

    fun encodeValue(dataType: PtpDataType, value: PtpPropertyValue): ByteArray =
        PtpPropertyCodec.encodeValue(dataType, value)

    private const val FORM_NONE = 0
    private const val FORM_RANGE = 1
    private const val FORM_ENUMERATION = 2
}

class MtpRatingContract private constructor(
    val dataType: PtpDataType,
) {
    fun wireValue(stars: Int): PtpPropertyValue.Unsigned {
        require(stars in MIN_STARS..MAX_STARS) { "Media rating must be from 0 through 5." }
        return PtpPropertyValue.Unsigned((stars * WIRE_POINTS_PER_STAR).toULong())
    }

    fun stars(value: PtpPropertyValue): Int? {
        val wire = (value as? PtpPropertyValue.Unsigned)?.value ?: return null
        if (wire > MAX_WIRE_RATING.toULong() || wire % WIRE_POINTS_PER_STAR.toULong() != 0UL) return null
        return (wire / WIRE_POINTS_PER_STAR.toULong()).toInt()
    }

    companion object {
        fun from(descriptor: MtpObjectPropertyDescriptor): MtpRatingContract? {
            if (
                descriptor.code != MtpObjectPropertyCode.RATING ||
                descriptor.dataType.code != PtpDataType.UINT16 ||
                !descriptor.writable
            ) {
                return null
            }
            val default = (descriptor.defaultValue as? PtpPropertyValue.Unsigned)?.value ?: return null
            if (default > MAX_WIRE_RATING.toULong()) return null
            val requiredValues = (MIN_STARS..MAX_STARS).map { (it * WIRE_POINTS_PER_STAR).toULong() }
            val allowedValues: (ULong) -> Boolean = when (val form = descriptor.form) {
                is PtpPropertyForm.Range -> {
                    val minimum = (form.minimum as? PtpPropertyValue.Unsigned)?.value ?: return null
                    val maximum = (form.maximum as? PtpPropertyValue.Unsigned)?.value ?: return null
                    val step = (form.step as? PtpPropertyValue.Unsigned)?.value ?: return null
                    if (minimum != 0UL || maximum != MAX_WIRE_RATING.toULong() || step == 0UL) return null
                    { value -> value in minimum..maximum && (value - minimum) % step == 0UL }
                }

                is PtpPropertyForm.Enumeration -> {
                    val values = form.values.mapNotNull { (it as? PtpPropertyValue.Unsigned)?.value }
                    if (values.size != form.values.size || values.any { it > MAX_WIRE_RATING.toULong() }) return null
                    values::contains
                }

                PtpPropertyForm.None -> return null
            }
            return if (allowedValues(default) && requiredValues.all(allowedValues)) {
                MtpRatingContract(descriptor.dataType)
            } else {
                null
            }
        }

        private const val MIN_STARS = 0
        private const val MAX_STARS = 5
        private const val MAX_WIRE_RATING = 100
        private const val WIRE_POINTS_PER_STAR = 20
    }
}

object PtpPropertyCodec {
    fun decodeDescriptor(bytes: ByteArray): PtpDevicePropertyDescriptor {
        val reader = PropertyReader(bytes)
        val propertyCode = reader.u16()
        val dataType = PtpDataType(reader.u16())
        val writable = when (val getSet = reader.u8()) {
            0 -> false
            1 -> true
            else -> throw PtpProtocolException("Invalid PTP DevicePropDesc GetSet value $getSet.")
        }
        val defaultValue = reader.value(dataType)
        val currentValue = reader.value(dataType)
        val form = if (!reader.hasRemaining()) {
            PtpPropertyForm.None
        } else {
            when (val formFlag = reader.u8()) {
                FORM_NONE -> PtpPropertyForm.None
                FORM_RANGE -> PtpPropertyForm.Range(
                    minimum = reader.value(dataType),
                    maximum = reader.value(dataType),
                    step = reader.value(dataType),
                )

                FORM_ENUMERATION -> {
                    val count = reader.u16()
                    PtpPropertyForm.Enumeration(List(count) { reader.value(dataType) })
                }

                else -> throw PtpProtocolException(
                    "Unsupported PTP DevicePropDesc form 0x${formFlag.toString(16).uppercase()} " +
                        "for property 0x${propertyCode.toString(16).uppercase()}."
                )
            }
        }
        return PtpDevicePropertyDescriptor(
            code = propertyCode,
            dataType = dataType,
            writable = writable,
            defaultValue = defaultValue,
            currentValue = currentValue,
            form = form,
        )
    }

    fun decodeValue(dataType: PtpDataType, bytes: ByteArray): PtpPropertyValue =
        PropertyReader(bytes).value(dataType)

    fun encodeValue(dataType: PtpDataType, value: PtpPropertyValue): ByteArray =
        ByteArrayOutputStream().also { output -> writeValue(output, dataType, value) }.toByteArray()

    private fun writeValue(output: ByteArrayOutputStream, dataType: PtpDataType, value: PtpPropertyValue) {
        if (dataType.isArray) {
            val values = (value as? PtpPropertyValue.Sequence)?.values
                ?: throw PtpProtocolException("PTP array data type requires a Sequence value.")
            writeUnsigned(output, values.size.toULong(), 4)
            val elementType = PtpDataType(dataType.elementCode)
            values.forEach { writeValue(output, elementType, it) }
            return
        }
        when (dataType.elementCode) {
            PtpDataType.INT8 -> writeSigned(output, value.signed(), 1)
            PtpDataType.UINT8 -> writeUnsigned(output, value.unsigned(), 1)
            PtpDataType.INT16 -> writeSigned(output, value.signed(), 2)
            PtpDataType.UINT16 -> writeUnsigned(output, value.unsigned(), 2)
            PtpDataType.INT32 -> writeSigned(output, value.signed(), 4)
            PtpDataType.UINT32 -> writeUnsigned(output, value.unsigned(), 4)
            PtpDataType.INT64 -> writeSigned(output, value.signed(), 8)
            PtpDataType.UINT64 -> writeUnsigned(output, value.unsigned(), 8)
            PtpDataType.INT128, PtpDataType.UINT128 -> {
                val raw = value as? PtpPropertyValue.Raw128
                    ?: throw PtpProtocolException("PTP 128-bit data type requires a Raw128 value.")
                if (raw.bytes.size != 16) throw PtpProtocolException("PTP 128-bit value must contain 16 bytes.")
                output.write(raw.bytes)
            }

            PtpDataType.STRING -> writeString(output, (value as? PtpPropertyValue.Text)?.value)
            else -> throw PtpProtocolException("Unsupported PTP property type 0x${dataType.code.toString(16)}.")
        }
    }

    private fun PtpPropertyValue.signed(): Long =
        (this as? PtpPropertyValue.Signed)?.value
            ?: throw PtpProtocolException("PTP signed data type requires a Signed value.")

    private fun PtpPropertyValue.unsigned(): ULong =
        (this as? PtpPropertyValue.Unsigned)?.value
            ?: throw PtpProtocolException("PTP unsigned data type requires an Unsigned value.")

    private fun writeSigned(output: ByteArrayOutputStream, value: Long, bytes: Int) {
        if (bytes < 8) {
            val bits = bytes * 8
            val minimum = -(1L shl (bits - 1))
            val maximum = (1L shl (bits - 1)) - 1L
            if (value !in minimum..maximum) {
                throw PtpProtocolException("Signed value $value does not fit in $bits bits.")
            }
        }
        repeat(bytes) { index -> output.write((value ushr (index * 8)).toInt() and 0xFF) }
    }

    private fun writeUnsigned(output: ByteArrayOutputStream, value: ULong, bytes: Int) {
        val maximum = if (bytes == 8) ULong.MAX_VALUE else (1UL shl (bytes * 8)) - 1UL
        if (value > maximum) throw PtpProtocolException("Unsigned value $value does not fit in ${bytes * 8} bits.")
        repeat(bytes) { index -> output.write(((value shr (index * 8)) and 0xFFUL).toInt()) }
    }

    private fun writeString(output: ByteArrayOutputStream, value: String?) {
        val text = value ?: throw PtpProtocolException("PTP string data type requires a Text value.")
        if (text.isEmpty()) {
            output.write(0)
            return
        }
        val encoded = text.toByteArray(StandardCharsets.UTF_16LE)
        val codeUnits = encoded.size / 2
        if (codeUnits >= 255) throw PtpProtocolException("PTP string exceeds 254 UTF-16 code units.")
        output.write(codeUnits + 1)
        output.write(encoded)
        output.write(0)
        output.write(0)
    }

    private const val FORM_NONE = 0
    private const val FORM_RANGE = 1
    private const val FORM_ENUMERATION = 2
}

private class PropertyReader(private val bytes: ByteArray) {
    private var offset = 0

    fun hasRemaining(): Boolean = offset < bytes.size

    fun u8(): Int {
        requireBytes(1)
        return bytes[offset++].toUByte().toInt()
    }

    fun u16(): Int = readUnsigned(2).toInt()

    fun u32(): ULong = readUnsigned(4)

    fun requireEnd(dataset: String) {
        if (offset != bytes.size) {
            throw PtpProtocolException("$dataset has ${bytes.size - offset} unexpected trailing bytes.")
        }
    }

    fun value(dataType: PtpDataType): PtpPropertyValue {
        if (dataType.isArray) {
            val count = readUnsigned(4)
            if (count > MAX_PROPERTY_ARRAY_VALUES.toULong()) {
                throw PtpProtocolException("PTP property array declares $count values; limit is $MAX_PROPERTY_ARRAY_VALUES.")
            }
            val elementType = PtpDataType(dataType.elementCode)
            return PtpPropertyValue.Sequence(List(count.toInt()) { value(elementType) })
        }
        return when (dataType.elementCode) {
            PtpDataType.INT8 -> PtpPropertyValue.Signed(readSigned(1))
            PtpDataType.UINT8 -> PtpPropertyValue.Unsigned(readUnsigned(1))
            PtpDataType.INT16 -> PtpPropertyValue.Signed(readSigned(2))
            PtpDataType.UINT16 -> PtpPropertyValue.Unsigned(readUnsigned(2))
            PtpDataType.INT32 -> PtpPropertyValue.Signed(readSigned(4))
            PtpDataType.UINT32 -> PtpPropertyValue.Unsigned(readUnsigned(4))
            PtpDataType.INT64 -> PtpPropertyValue.Signed(readSigned(8))
            PtpDataType.UINT64 -> PtpPropertyValue.Unsigned(readUnsigned(8))
            PtpDataType.INT128, PtpDataType.UINT128 -> {
                requireBytes(16)
                PtpPropertyValue.Raw128(
                    bytes = bytes.copyOfRange(offset, offset + 16).also { offset += 16 },
                    signed = dataType.elementCode == PtpDataType.INT128,
                )
            }

            PtpDataType.STRING -> PtpPropertyValue.Text(readString())
            else -> throw PtpProtocolException("Unsupported PTP property data type 0x${dataType.code.toString(16)}.")
        }
    }

    private fun readSigned(byteCount: Int): Long {
        val unsigned = readUnsigned(byteCount)
        if (byteCount == 8) return unsigned.toLong()
        val bits = byteCount * 8
        val signBit = 1UL shl (bits - 1)
        return if (unsigned and signBit == 0UL) {
            unsigned.toLong()
        } else {
            (unsigned - (1UL shl bits)).toLong()
        }
    }

    private fun readUnsigned(byteCount: Int): ULong {
        requireBytes(byteCount)
        var value = 0UL
        repeat(byteCount) { index ->
            value = value or (bytes[offset + index].toUByte().toULong() shl (index * 8))
        }
        offset += byteCount
        return value
    }

    private fun readString(): String {
        val codeUnits = u8()
        if (codeUnits == 0) return ""
        val byteCount = codeUnits * 2
        requireBytes(byteCount)
        val text = String(bytes, offset, byteCount - 2, StandardCharsets.UTF_16LE).trimEnd('\u0000')
        offset += byteCount
        return text
    }

    private fun requireBytes(count: Int) {
        if (count < 0 || offset > bytes.size - count) {
            throw PtpProtocolException(
                "PTP property dataset ended at byte $offset; $count more bytes were required (${bytes.size} total)."
            )
        }
    }
}

private const val MAX_PROPERTY_ARRAY_VALUES = 100_000
private const val MAX_OBJECT_PROPERTY_CODES = 65_536
