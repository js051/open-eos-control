package dev.openeos.control.data

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class PtpPropertyOption(
    val label: String,
    val value: PtpPropertyValue,
)

data class PtpAdvancedPropertySpec(
    val propertyCode: Int,
    val key: String,
    val fallbackLabel: String,
)

object PtpStandardProperties {
    val statusPropertyCodes = setOf(
        PtpDevicePropertyCode.BATTERY_LEVEL,
        PtpDevicePropertyCode.WHITE_BALANCE,
        PtpDevicePropertyCode.F_NUMBER,
        PtpDevicePropertyCode.EXPOSURE_TIME,
        PtpDevicePropertyCode.EXPOSURE_PROGRAM_MODE,
        PtpDevicePropertyCode.EXPOSURE_INDEX,
    )

    val knownPropertyCodes = statusPropertyCodes + setOf(
        PtpDevicePropertyCode.COMPRESSION_SETTING,
        PtpDevicePropertyCode.FOCUS_MODE,
        PtpDevicePropertyCode.EXPOSURE_METERING_MODE,
        PtpDevicePropertyCode.FLASH_MODE,
        PtpDevicePropertyCode.EXPOSURE_BIAS_COMPENSATION,
        PtpDevicePropertyCode.STILL_CAPTURE_MODE,
    )

    val advancedProperties = listOf(
        PtpAdvancedPropertySpec(
            PtpDevicePropertyCode.FOCUS_MODE,
            key = "afoperation",
            fallbackLabel = "Focus mode",
        ),
        PtpAdvancedPropertySpec(
            PtpDevicePropertyCode.EXPOSURE_METERING_MODE,
            key = "meteringmode",
            fallbackLabel = "Exposure metering",
        ),
        PtpAdvancedPropertySpec(
            PtpDevicePropertyCode.FLASH_MODE,
            key = "flashmode",
            fallbackLabel = "Flash mode",
        ),
        PtpAdvancedPropertySpec(
            PtpDevicePropertyCode.EXPOSURE_PROGRAM_MODE,
            key = "shootingmode",
            fallbackLabel = "Exposure program",
        ),
        PtpAdvancedPropertySpec(
            PtpDevicePropertyCode.STILL_CAPTURE_MODE,
            key = "drivemode",
            fallbackLabel = "Drive mode",
        ),
        PtpAdvancedPropertySpec(
            PtpDevicePropertyCode.COMPRESSION_SETTING,
            key = "stillimagequality",
            fallbackLabel = "Image quality",
        ),
        PtpAdvancedPropertySpec(
            PtpDevicePropertyCode.EXPOSURE_BIAS_COMPENSATION,
            key = "exposurecompensation",
            fallbackLabel = "Exposure compensation",
        ),
    )

    fun options(descriptor: PtpDevicePropertyDescriptor): List<PtpPropertyOption> {
        val values = when (val form = descriptor.form) {
            is PtpPropertyForm.Enumeration -> form.values
            is PtpPropertyForm.Range -> rangeValues(form)
            PtpPropertyForm.None -> emptyList()
        }
        val unique = LinkedHashMap<String, PtpPropertyValue>()
        values.forEach { value ->
            val baseLabel = format(descriptor.code, value)
            val label = if (baseLabel in unique && unique[baseLabel] != value) {
                "$baseLabel (${rawLabel(value)})"
            } else {
                baseLabel
            }
            unique[label] = value
        }
        return unique.map { (label, value) -> PtpPropertyOption(label, value) }
    }

    fun format(propertyCode: Int, value: PtpPropertyValue): String = when (propertyCode) {
        PtpDevicePropertyCode.BATTERY_LEVEL -> value.unsignedLong()?.let { "$it%" } ?: rawLabel(value)
        PtpDevicePropertyCode.EXPOSURE_INDEX -> formatIso(value)
        PtpDevicePropertyCode.F_NUMBER -> formatFNumber(value)
        PtpDevicePropertyCode.EXPOSURE_TIME -> formatExposureTime(value)
        PtpDevicePropertyCode.WHITE_BALANCE -> formatWhiteBalance(value)
        PtpDevicePropertyCode.EXPOSURE_BIAS_COMPENSATION -> formatExposureCompensation(value)
        PtpDevicePropertyCode.FOCUS_MODE -> standardLabel(
            value,
            mapOf(0L to "undefined", 1L to "manual", 2L to "auto", 3L to "auto macro"),
        )

        PtpDevicePropertyCode.EXPOSURE_METERING_MODE -> standardLabel(
            value,
            mapOf(1L to "average", 2L to "center weighted", 3L to "multi spot", 4L to "center spot"),
        )

        PtpDevicePropertyCode.FLASH_MODE -> standardLabel(
            value,
            mapOf(
                1L to "auto",
                2L to "off",
                3L to "fill",
                4L to "red-eye auto",
                5L to "red-eye fill",
                6L to "external sync",
            ),
        )

        PtpDevicePropertyCode.EXPOSURE_PROGRAM_MODE -> standardLabel(
            value,
            mapOf(1L to "M", 2L to "P", 3L to "A", 4L to "S", 5L to "creative", 6L to "action", 7L to "portrait"),
        )

        PtpDevicePropertyCode.STILL_CAPTURE_MODE -> standardLabel(
            value,
            mapOf(1L to "single", 2L to "burst", 3L to "timelapse"),
        )

        else -> rawLabel(value)
    }

    private fun rangeValues(range: PtpPropertyForm.Range): List<PtpPropertyValue> {
        val signedMinimum = (range.minimum as? PtpPropertyValue.Signed)?.value
        val signedMaximum = (range.maximum as? PtpPropertyValue.Signed)?.value
        val signedStep = (range.step as? PtpPropertyValue.Signed)?.value
        if (signedMinimum != null && signedMaximum != null && signedStep != null && signedStep > 0L) {
            if (signedMaximum < signedMinimum) return emptyList()
            val span = runCatching { Math.subtractExact(signedMaximum, signedMinimum) }.getOrNull()
                ?: return emptyList()
            val intervals = span / signedStep
            if (intervals >= MAX_SELECTABLE_PROPERTY_VALUES.toLong()) return emptyList()
            return List(intervals.toInt() + 1) { index ->
                PtpPropertyValue.Signed(signedMinimum + signedStep * index)
            }
        }

        val minimum = (range.minimum as? PtpPropertyValue.Unsigned)?.value ?: return emptyList()
        val maximum = (range.maximum as? PtpPropertyValue.Unsigned)?.value ?: return emptyList()
        val step = (range.step as? PtpPropertyValue.Unsigned)?.value ?: return emptyList()
        if (step == 0UL || maximum < minimum) return emptyList()
        val intervals = (maximum - minimum) / step
        if (intervals >= MAX_SELECTABLE_PROPERTY_VALUES.toULong()) return emptyList()
        return List(intervals.toInt() + 1) { index ->
            PtpPropertyValue.Unsigned(minimum + step * index.toULong())
        }
    }

    private fun formatIso(value: PtpPropertyValue): String {
        val raw = value.unsignedLong() ?: return rawLabel(value)
        return when (raw) {
            0UL, 0xFFFFUL -> "auto"
            else -> raw.toString()
        }
    }

    private fun formatFNumber(value: PtpPropertyValue): String {
        val raw = value.unsignedLong() ?: return rawLabel(value)
        if (raw == 0UL) return "auto"
        return compactDecimal(raw.toDouble() / 100.0)
    }

    private fun formatExposureTime(value: PtpPropertyValue): String {
        val raw = value.unsignedLong() ?: return rawLabel(value)
        if (raw == 0UL) return "auto"
        if (raw == 0xFFFF_FFFFUL) return "bulb"
        if (raw == 0xFFFF_FFFDUL) return "time"
        val seconds = raw.toDouble() / 10_000.0
        if (seconds <= 0.0) return raw.toString()
        if (seconds < 1.0) {
            val denominator = (1.0 / seconds).roundToInt().coerceAtLeast(1)
            val reconstructed = 1.0 / denominator
            if (abs(reconstructed - seconds) / seconds < 0.01) return "1/$denominator"
        }
        return "${compactDecimal(seconds)}s"
    }

    private fun formatWhiteBalance(value: PtpPropertyValue): String = standardLabel(
        value,
        mapOf(
            1L to "manual",
            2L to "auto",
            3L to "one-push auto",
            4L to "daylight",
            5L to "fluorescent",
            6L to "tungsten",
            7L to "flash",
        ),
    )

    private fun formatExposureCompensation(value: PtpPropertyValue): String {
        val raw = (value as? PtpPropertyValue.Signed)?.value ?: return rawLabel(value)
        val ev = raw / 1000.0
        return if (ev > 0.0) "+${compactDecimal(ev)}" else compactDecimal(ev)
    }

    private fun standardLabel(value: PtpPropertyValue, labels: Map<Long, String>): String {
        val raw = value.longValue() ?: return rawLabel(value)
        return labels[raw] ?: if (raw >= 0x8000L) "0x${raw.toString(16).uppercase()}" else raw.toString()
    }

    private fun rawLabel(value: PtpPropertyValue): String = when (value) {
        is PtpPropertyValue.Signed -> value.value.toString()
        is PtpPropertyValue.Unsigned -> value.value.toString()
        is PtpPropertyValue.Text -> value.value
        is PtpPropertyValue.Raw128 -> value.bytes.joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xFF) }
        is PtpPropertyValue.Sequence -> value.values.joinToString(",") { rawLabel(it) }
    }

    private fun PtpPropertyValue.longValue(): Long? = when (this) {
        is PtpPropertyValue.Signed -> value
        is PtpPropertyValue.Unsigned -> value.takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong()
        else -> null
    }

    private fun PtpPropertyValue.unsignedLong(): ULong? = when (this) {
        is PtpPropertyValue.Unsigned -> value
        is PtpPropertyValue.Signed -> value.takeIf { it >= 0L }?.toULong()
        else -> null
    }

    private fun compactDecimal(value: Double): String =
        String.format(Locale.ROOT, "%.4f", value).trimEnd('0').trimEnd('.')

    private const val MAX_SELECTABLE_PROPERTY_VALUES = 256
}
