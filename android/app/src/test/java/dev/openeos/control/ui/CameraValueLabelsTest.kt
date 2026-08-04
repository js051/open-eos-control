package dev.openeos.control.ui

import dev.openeos.control.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraValueLabelsTest {
    @Test
    fun movieQualityTokenUsesReadableCameraStyleSummary() {
        assertEquals(
            "3840x2160 / 59.94p / IPB",
            movieQualityDisplayValue("3840x2160_5994_ipb_standard"),
        )
        assertEquals(
            "FHD / 29.97p / IPB / Lite / Cropped",
            movieQualityDisplayValue("fhd_2997_ipb_light_crop", lightLabel = "Lite", cropLabel = "Cropped"),
        )
        assertEquals("4096x2160 / 120.00p / ALL-I", movieQualityDisplayValue("4096x2160_12000_alli_standard"))
        assertNull(movieQualityDisplayValue("4K Fine 59.94p"))
    }

    @Test
    fun resolvesCameraAdvertisedValuesBySettingContext() {
        val cases = listOf(
            Triple("whitebalance", "Shadow", R.string.camera_value_shade),
            Triple("white_balance", "AWB White", R.string.camera_value_awb_white),
            Triple("afoperation", "One Shot", R.string.camera_value_one_shot_af),
            Triple("afmethod", "WholeAreaAF", R.string.camera_value_whole_area_af),
            Triple("continuousaf", "On", R.string.camera_value_on),
            Triple("drivemode", "Super high speed continuous shooting", R.string.camera_value_super_high_speed_continuous),
            Triple("meteringmode", "Evaluative", R.string.camera_value_evaluative_metering),
            Triple("picturestyle", "Fine detail", R.string.camera_value_fine_detail),
            Triple("highisonr", "Multi-Shot", R.string.camera_value_multi_shot),
            Triple("alomode", "Standard", R.string.camera_value_standard),
            Triple(
                "alomode",
                "High (disabled in manual exposure)",
                R.string.camera_value_high_disabled_manual,
            ),
            Triple("autopoweroff", "30 minutes", R.string.camera_value_30_minutes),
            Triple("autopoweroff", "30", R.string.camera_value_30_seconds),
            Triple("autopoweroff", "120", R.string.camera_value_2_minutes),
            Triple("autopoweroff", "Disable", R.string.camera_value_disable),
            Triple("beep", "disabletouch", R.string.camera_value_disable_touch),
            Triple("displayoff", "20", R.string.camera_value_20_seconds),
            Triple("displayoff", "120", R.string.camera_value_2_minutes),
            Triple("capturetarget", "Internal RAM", R.string.camera_value_internal_ram),
            Triple("capturetarget", "Phone", R.string.camera_value_phone),
            Triple("capturetarget", "Memory card", R.string.camera_value_memory_card),
            Triple("capturestorage", "Card 1", R.string.camera_value_card_1),
            Triple("capturestorage", "Card 2", R.string.camera_value_card_2),
            Triple("cardselectionstillimage", "card1", R.string.camera_value_card_1),
            Triple("cardselectionmovie", "card2", R.string.camera_value_card_2),
            Triple("cardselectionmovie", "none", R.string.camera_value_none),
            Triple("soundrecording", "manual", R.string.camera_value_manual),
            Triple("windfilter", "enable", R.string.camera_value_enable),
            Triple("attenuator", "disable", R.string.camera_value_disable),
            Triple("aeb", "off", R.string.camera_value_off),
            Triple("stillimagequalitycf", "cRAW + Large Fine JPEG", R.string.camera_value_craw_large_fine_jpeg),
            Triple("stillimagequality.raw", "none", R.string.camera_value_none),
            Triple("stillimagequality.jpeg", "large_fine", R.string.camera_value_large_fine),
            Triple("shootingmode", "TV", R.string.camera_value_shutter_priority_ae),
            Triple("autoexposuremode", "Fv", R.string.camera_value_flexible_priority_ae),
        )

        cases.forEach { (key, value, expectedResource) ->
            assertEquals(expectedResource, cameraValueLabelResource(key, value))
        }
    }

    @Test
    fun normalizesProtocolAliasesWithoutChangingUnknownValues() {
        assertEquals(
            R.string.camera_value_one_shot_af,
            cameraValueLabelResource("AF-OPERATION", "one-shot"),
        )
        assertEquals(
            R.string.camera_value_one_point_af,
            cameraValueLabelResource("af_method", "1-point AF"),
        )
        assertNull(cameraValueLabelResource("moviequality", "4K Fine 59.94p"))
        assertNull(cameraValueLabelResource("vendorExtension", "Auto"))
    }
}
