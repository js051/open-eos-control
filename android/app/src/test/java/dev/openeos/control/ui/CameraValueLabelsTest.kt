package dev.openeos.control.ui

import dev.openeos.control.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraValueLabelsTest {
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
            Triple("stillimagequalitycf", "cRAW + Large Fine JPEG", R.string.camera_value_craw_large_fine_jpeg),
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
