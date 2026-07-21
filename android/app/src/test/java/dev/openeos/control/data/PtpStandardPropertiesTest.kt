package dev.openeos.control.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PtpStandardPropertiesTest {
    @Test
    fun exposureValuesUseCameraFriendlyLabelsWithoutChangingRawValues() {
        assertEquals(
            "800",
            PtpStandardProperties.format(PtpDevicePropertyCode.EXPOSURE_INDEX, unsigned(800)),
        )
        assertEquals(
            "2.8",
            PtpStandardProperties.format(PtpDevicePropertyCode.F_NUMBER, unsigned(280)),
        )
        assertEquals(
            "1/50",
            PtpStandardProperties.format(PtpDevicePropertyCode.EXPOSURE_TIME, unsigned(200)),
        )
        assertEquals(
            "1.5s",
            PtpStandardProperties.format(PtpDevicePropertyCode.EXPOSURE_TIME, unsigned(15_000)),
        )
        assertEquals(
            "daylight",
            PtpStandardProperties.format(PtpDevicePropertyCode.WHITE_BALANCE, unsigned(4)),
        )
        assertEquals(
            "+0.333",
            PtpStandardProperties.format(
                PtpDevicePropertyCode.EXPOSURE_BIAS_COMPENSATION,
                PtpPropertyValue.Signed(333),
            ),
        )
    }

    @Test
    fun optionsPreserveAdvertisedEnumerationValuesForWrites() {
        val descriptor = descriptor(
            code = PtpDevicePropertyCode.F_NUMBER,
            form = PtpPropertyForm.Enumeration(listOf(unsigned(200), unsigned(280), unsigned(400))),
        )

        val options = PtpStandardProperties.options(descriptor)

        assertEquals(listOf("2", "2.8", "4"), options.map { it.label })
        assertEquals(listOf(unsigned(200), unsigned(280), unsigned(400)), options.map { it.value })
    }

    @Test
    fun smallRangesBecomeOptionsButUnboundedRangesStayNonInteractive() {
        val small = descriptor(
            code = PtpDevicePropertyCode.EXPOSURE_INDEX,
            form = PtpPropertyForm.Range(unsigned(100), unsigned(400), unsigned(100)),
        )
        val large = descriptor(
            code = PtpDevicePropertyCode.EXPOSURE_INDEX,
            form = PtpPropertyForm.Range(unsigned(1), unsigned(100_000), unsigned(1)),
        )

        assertEquals(listOf("100", "200", "300", "400"), PtpStandardProperties.options(small).map { it.label })
        assertTrue(PtpStandardProperties.options(large).isEmpty())
    }

    @Test
    fun extremeRangesDoNotOverflowWhileBuildingOptions() {
        val signed = PtpDevicePropertyDescriptor(
            code = PtpDevicePropertyCode.EXPOSURE_BIAS_COMPENSATION,
            dataType = PtpDataType(PtpDataType.INT64),
            writable = true,
            defaultValue = PtpPropertyValue.Signed(0),
            currentValue = PtpPropertyValue.Signed(0),
            form = PtpPropertyForm.Range(
                PtpPropertyValue.Signed(Long.MIN_VALUE),
                PtpPropertyValue.Signed(Long.MAX_VALUE),
                PtpPropertyValue.Signed(1),
            ),
        )
        val unsigned = PtpDevicePropertyDescriptor(
            code = PtpDevicePropertyCode.EXPOSURE_INDEX,
            dataType = PtpDataType(PtpDataType.UINT64),
            writable = true,
            defaultValue = PtpPropertyValue.Unsigned(0UL),
            currentValue = PtpPropertyValue.Unsigned(0UL),
            form = PtpPropertyForm.Range(
                PtpPropertyValue.Unsigned(0UL),
                PtpPropertyValue.Unsigned(ULong.MAX_VALUE),
                PtpPropertyValue.Unsigned(1UL),
            ),
        )

        assertTrue(PtpStandardProperties.options(signed).isEmpty())
        assertTrue(PtpStandardProperties.options(unsigned).isEmpty())
    }

    @Test
    fun unknownVendorValuesRemainExplicitHexTokens() {
        assertEquals(
            "0x8010",
            PtpStandardProperties.format(PtpDevicePropertyCode.WHITE_BALANCE, unsigned(0x8010)),
        )
    }

    private fun descriptor(code: Int, form: PtpPropertyForm) = PtpDevicePropertyDescriptor(
        code = code,
        dataType = PtpDataType(PtpDataType.UINT16),
        writable = true,
        defaultValue = unsigned(100),
        currentValue = unsigned(100),
        form = form,
    )

    private fun unsigned(value: Long) = PtpPropertyValue.Unsigned(value.toULong())
}
