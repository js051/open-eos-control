package dev.openeos.control.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class PtpPropertiesTest {
    @Test
    fun descriptorParserReadsWritableEnumeration() {
        val bytes = PropertyWriter().apply {
            u16(PtpDevicePropertyCode.WHITE_BALANCE)
            u16(PtpDataType.UINT16)
            u8(1)
            u16(2)
            u16(4)
            u8(2)
            u16(4)
            u16(1)
            u16(2)
            u16(4)
            u16(7)
        }.bytes()

        val descriptor = PtpPropertyCodec.decodeDescriptor(bytes)

        assertEquals(PtpDevicePropertyCode.WHITE_BALANCE, descriptor.code)
        assertTrue(descriptor.writable)
        assertEquals(PtpPropertyValue.Unsigned(2UL), descriptor.defaultValue)
        assertEquals(PtpPropertyValue.Unsigned(4UL), descriptor.currentValue)
        assertEquals(
            listOf(1UL, 2UL, 4UL, 7UL).map(PtpPropertyValue::Unsigned),
            (descriptor.form as PtpPropertyForm.Enumeration).values,
        )
    }

    @Test
    fun descriptorParserPreservesSignedRangeValues() {
        val bytes = PropertyWriter().apply {
            u16(PtpDevicePropertyCode.EXPOSURE_BIAS_COMPENSATION)
            u16(PtpDataType.INT16)
            u8(0)
            i16(0)
            i16(333)
            u8(1)
            i16(-3000)
            i16(3000)
            i16(333)
        }.bytes()

        val descriptor = PtpPropertyCodec.decodeDescriptor(bytes)
        val range = descriptor.form as PtpPropertyForm.Range

        assertFalse(descriptor.writable)
        assertEquals(PtpPropertyValue.Signed(333), descriptor.currentValue)
        assertEquals(PtpPropertyValue.Signed(-3000), range.minimum)
        assertEquals(PtpPropertyValue.Signed(3000), range.maximum)
        assertEquals(PtpPropertyValue.Signed(333), range.step)
    }

    @Test
    fun valueCodecRoundTripsUnsignedSignedStringAndArrayTypes() {
        val unsignedType = PtpDataType(PtpDataType.UINT32)
        val signedType = PtpDataType(PtpDataType.INT16)
        val stringType = PtpDataType(PtpDataType.STRING)
        val arrayType = PtpDataType(PtpDataType.ARRAY_MASK or PtpDataType.UINT16)

        assertEquals(
            PtpPropertyValue.Unsigned(4_000_000_000UL),
            PtpPropertyCodec.decodeValue(
                unsignedType,
                PtpPropertyCodec.encodeValue(unsignedType, PtpPropertyValue.Unsigned(4_000_000_000UL)),
            ),
        )
        assertEquals(
            PtpPropertyValue.Signed(-1000),
            PtpPropertyCodec.decodeValue(
                signedType,
                PtpPropertyCodec.encodeValue(signedType, PtpPropertyValue.Signed(-1000)),
            ),
        )
        assertEquals(
            PtpPropertyValue.Text("EOS R6 Mark III"),
            PtpPropertyCodec.decodeValue(
                stringType,
                PtpPropertyCodec.encodeValue(stringType, PtpPropertyValue.Text("EOS R6 Mark III")),
            ),
        )
        val sequence = PtpPropertyValue.Sequence(
            listOf(100UL, 200UL, 400UL).map(PtpPropertyValue::Unsigned)
        )
        assertEquals(
            sequence,
            PtpPropertyCodec.decodeValue(arrayType, PtpPropertyCodec.encodeValue(arrayType, sequence)),
        )
    }

    @Test
    fun uint16EncodingIsLittleEndian() {
        assertArrayEquals(
            byteArrayOf(0x20, 0x03),
            PtpPropertyCodec.encodeValue(
                PtpDataType(PtpDataType.UINT16),
                PtpPropertyValue.Unsigned(800UL),
            ),
        )
    }

    @Test
    fun objectPropertyCodecReadsSupportedCodesAndWritableRatingRange() {
        val supported = PropertyWriter().apply {
            u32(2)
            u16(MtpObjectPropertyCode.RATING)
            u16(0xDC03)
        }.bytes()
        val descriptorBytes = PropertyWriter().apply {
            u16(MtpObjectPropertyCode.RATING)
            u16(PtpDataType.UINT16)
            u8(1)
            u16(0)
            u32(0)
            u8(1)
            u16(0)
            u16(100)
            u16(1)
        }.bytes()

        val descriptor = MtpObjectPropertyCodec.decodeDescriptor(descriptorBytes)

        assertEquals(setOf(MtpObjectPropertyCode.RATING, 0xDC03), MtpObjectPropertyCodec.decodeSupportedProperties(supported))
        assertEquals(MtpObjectPropertyCode.RATING, descriptor.code)
        assertEquals(PtpDataType(PtpDataType.UINT16), descriptor.dataType)
        assertTrue(descriptor.writable)
        assertEquals(PtpPropertyValue.Unsigned(0UL), descriptor.defaultValue)
        assertEquals(0L, descriptor.groupCode)
        assertEquals(
            PtpPropertyForm.Range(
                minimum = PtpPropertyValue.Unsigned(0UL),
                maximum = PtpPropertyValue.Unsigned(100UL),
                step = PtpPropertyValue.Unsigned(1UL),
            ),
            descriptor.form,
        )
    }

    @Test
    fun objectPropertyCodecRejectsTrailingOrUnsupportedDescriptorData() {
        val descriptorWithTrailingByte = PropertyWriter().apply {
            u16(MtpObjectPropertyCode.RATING)
            u16(PtpDataType.UINT16)
            u8(1)
            u16(0)
            u32(0)
            u8(0)
            u8(0x7F)
        }.bytes()
        val unsupportedForm = PropertyWriter().apply {
            u16(MtpObjectPropertyCode.RATING)
            u16(PtpDataType.UINT16)
            u8(1)
            u16(0)
            u32(0)
            u8(3)
        }.bytes()

        val trailingFailure = runCatching {
            MtpObjectPropertyCodec.decodeDescriptor(descriptorWithTrailingByte)
        }.exceptionOrNull()
        val formFailure = runCatching {
            MtpObjectPropertyCodec.decodeDescriptor(unsupportedForm)
        }.exceptionOrNull()
        val valueFailure = runCatching {
            MtpObjectPropertyCodec.decodeValue(
                PtpDataType(PtpDataType.UINT16),
                byteArrayOf(40, 0, 1),
            )
        }.exceptionOrNull()

        assertTrue(trailingFailure is PtpProtocolException)
        assertTrue(formFailure is PtpProtocolException)
        assertTrue(valueFailure is PtpProtocolException)
    }

    @Test
    fun ratingContractMapsFiveStarsToExplicitMtpPercentValues() {
        val contract = MtpRatingContract.from(
            MtpObjectPropertyDescriptor(
                code = MtpObjectPropertyCode.RATING,
                dataType = PtpDataType(PtpDataType.UINT16),
                writable = true,
                defaultValue = PtpPropertyValue.Unsigned(0UL),
                groupCode = 0,
                form = PtpPropertyForm.Range(
                    minimum = PtpPropertyValue.Unsigned(0UL),
                    maximum = PtpPropertyValue.Unsigned(100UL),
                    step = PtpPropertyValue.Unsigned(1UL),
                ),
            ),
        ) ?: error("Expected a rating contract.")

        assertEquals(
            listOf(0UL, 20UL, 40UL, 60UL, 80UL, 100UL).map(PtpPropertyValue::Unsigned),
            (0..5).map(contract::wireValue),
        )
        assertEquals(4, contract.stars(PtpPropertyValue.Unsigned(80UL)))
        assertEquals(null, contract.stars(PtpPropertyValue.Unsigned(37UL)))
    }

    @Test
    fun ratingContractRejectsReadonlyWrongTypeAndUnprovenValues() {
        fun descriptor(
            dataType: Int = PtpDataType.UINT16,
            writable: Boolean = true,
            form: PtpPropertyForm,
        ) = MtpObjectPropertyDescriptor(
            code = MtpObjectPropertyCode.RATING,
            dataType = PtpDataType(dataType),
            writable = writable,
            defaultValue = PtpPropertyValue.Unsigned(0UL),
            groupCode = 0,
            form = form,
        )

        val standardRange = PtpPropertyForm.Range(
            minimum = PtpPropertyValue.Unsigned(0UL),
            maximum = PtpPropertyValue.Unsigned(100UL),
            step = PtpPropertyValue.Unsigned(1UL),
        )
        val starsOnly = PtpPropertyForm.Enumeration(
            (0UL..5UL).map(PtpPropertyValue::Unsigned),
        )

        assertEquals(null, MtpRatingContract.from(descriptor(writable = false, form = standardRange)))
        assertEquals(null, MtpRatingContract.from(descriptor(dataType = PtpDataType.UINT32, form = standardRange)))
        assertEquals(null, MtpRatingContract.from(descriptor(form = PtpPropertyForm.None)))
        assertEquals(null, MtpRatingContract.from(descriptor(form = starsOnly)))
    }

    private class PropertyWriter {
        private val output = ByteArrayOutputStream()

        fun u8(value: Int) {
            output.write(value and 0xFF)
        }

        fun u16(value: Int) {
            output.write(value and 0xFF)
            output.write((value ushr 8) and 0xFF)
        }

        fun u32(value: Long) {
            repeat(4) { index -> output.write(((value ushr (index * 8)) and 0xFF).toInt()) }
        }

        fun i16(value: Int) = u16(value and 0xFFFF)

        @Suppress("unused")
        fun string(value: String) {
            if (value.isEmpty()) {
                u8(0)
                return
            }
            val encoded = value.toByteArray(StandardCharsets.UTF_16LE)
            u8(encoded.size / 2 + 1)
            output.write(encoded)
            u16(0)
        }

        fun bytes(): ByteArray = output.toByteArray()
    }
}
