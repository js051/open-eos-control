package dev.openeos.control.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.openeos.control.R
import java.util.Locale

@Composable
internal fun localizedCameraValue(settingKey: String?, rawValue: String): String {
    val resource = cameraValueLabelResource(settingKey, rawValue) ?: return rawValue
    return stringResource(resource)
}

@StringRes
internal fun cameraValueLabelResource(settingKey: String?, rawValue: String): Int? {
    val key = settingKey.orEmpty().normalizedCameraKey()
    val value = rawValue.normalizedCameraValue()
    return when (key) {
        "iso", "shutter", "shutterspeed", "tv", "aperture", "av" ->
            exposureValueLabels[value]

        "whitebalance", "wb" -> whiteBalanceValueLabels[value]
        "afoperation", "focusmode" -> afOperationValueLabels[value]
        "afmethod" -> afMethodValueLabels[value]
        "continuousaf", "movieservoaf" -> toggleValueLabels[value]
        "drivemode" -> driveModeValueLabels[value]
        "meteringmode" -> meteringModeValueLabels[value]
        "flashmode" -> flashModeValueLabels[value]
        "picturestyle" -> pictureStyleValueLabels[value]
        "highisonr" -> highIsoNoiseReductionValueLabels[value]
        "aeb" -> toggleValueLabels[value]
        "stillimagequality", "stillimagequalitysd", "stillimagequalitycf" ->
            imageQualityValueLabels[value]

        "shootingmode", "autoexposuremode", "ae" -> shootingModeValueLabels[value]
        else -> null
    }
}

private fun String.normalizedCameraKey(): String =
    lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

private fun String.normalizedCameraValue(): String =
    trim()
        .lowercase(Locale.ROOT)
        .replace('_', ' ')
        .replace('-', ' ')
        .replace(WHITESPACE, " ")

private val exposureValueLabels = mapOf(
    "auto" to R.string.camera_value_auto,
    "bulb" to R.string.camera_value_bulb,
    "time" to R.string.camera_value_time,
)

private val whiteBalanceValueLabels = mapOf(
    "auto" to R.string.camera_value_auto,
    "daylight" to R.string.camera_value_daylight,
    "shade" to R.string.camera_value_shade,
    "shadow" to R.string.camera_value_shade,
    "cloudy" to R.string.camera_value_cloudy,
    "tungsten" to R.string.camera_value_tungsten,
    "fluorescent" to R.string.camera_value_fluorescent,
    "flash" to R.string.camera_value_flash,
    "manual" to R.string.camera_value_custom_white_balance,
    "manual 2" to R.string.camera_value_manual_2,
    "manual 3" to R.string.camera_value_manual_3,
    "manual 4" to R.string.camera_value_manual_4,
    "manual 5" to R.string.camera_value_manual_5,
    "one push auto" to R.string.camera_value_one_push_auto,
    "color temperature" to R.string.camera_value_color_temperature,
    "custom wb 1" to R.string.camera_value_custom_wb_1,
    "custom wb 2" to R.string.camera_value_custom_wb_2,
    "custom wb 3" to R.string.camera_value_custom_wb_3,
    "custom wb 4" to R.string.camera_value_custom_wb_4,
    "custom wb 5" to R.string.camera_value_custom_wb_5,
    "awb white" to R.string.camera_value_awb_white,
)

private val toggleValueLabels = mapOf(
    "off" to R.string.camera_value_off,
    "on" to R.string.camera_value_on,
    "disabled" to R.string.camera_value_off,
    "enabled" to R.string.camera_value_on,
)

private val afOperationValueLabels = mapOf(
    "one shot" to R.string.camera_value_one_shot_af,
    "servo" to R.string.camera_value_servo_af,
    "ai servo" to R.string.camera_value_servo_af,
    "ai focus" to R.string.camera_value_ai_focus,
    "manual" to R.string.camera_value_manual_focus,
    "auto" to R.string.camera_value_auto_focus,
    "auto macro" to R.string.camera_value_auto_macro,
)

private val afMethodValueLabels = mapOf(
    "quick" to R.string.camera_value_quick_af,
    "live" to R.string.camera_value_live_af,
    "liveface" to R.string.camera_value_face_tracking_af,
    "face+tracking" to R.string.camera_value_face_tracking_af,
    "livemulti" to R.string.camera_value_multi_af,
    "livezone" to R.string.camera_value_zone_af,
    "zone" to R.string.camera_value_zone_af,
    "zone af" to R.string.camera_value_zone_af,
    "livesingleexpandcross" to R.string.camera_value_expand_cross_af,
    "expand af area" to R.string.camera_value_expand_cross_af,
    "livesingleexpandsurround" to R.string.camera_value_expand_surround_af,
    "expand af area:around" to R.string.camera_value_expand_surround_af,
    "livezonelargeh" to R.string.camera_value_large_zone_horizontal_af,
    "large zone af:horizontal" to R.string.camera_value_large_zone_horizontal_af,
    "livezonelargev" to R.string.camera_value_large_zone_vertical_af,
    "large zone af:vertical" to R.string.camera_value_large_zone_vertical_af,
    "livecatchaf" to R.string.camera_value_catch_af,
    "livespotaf" to R.string.camera_value_spot_af,
    "spot af" to R.string.camera_value_spot_af,
    "1 point" to R.string.camera_value_one_point_af,
    "1 point af" to R.string.camera_value_one_point_af,
    "flexiblezoneaf1" to R.string.camera_value_flexible_zone_af_1,
    "flexible zone 1" to R.string.camera_value_flexible_zone_af_1,
    "flexiblezoneaf2" to R.string.camera_value_flexible_zone_af_2,
    "flexible zone 2" to R.string.camera_value_flexible_zone_af_2,
    "flexiblezoneaf3" to R.string.camera_value_flexible_zone_af_3,
    "flexible zone 3" to R.string.camera_value_flexible_zone_af_3,
    "wholeareaaf" to R.string.camera_value_whole_area_af,
    "whole area af" to R.string.camera_value_whole_area_af,
)

private val driveModeValueLabels = mapOf(
    "single" to R.string.camera_value_single_shooting,
    "continuous" to R.string.camera_value_continuous_shooting,
    "burst" to R.string.camera_value_continuous_shooting,
    "video" to R.string.camera_value_video_recording,
    "continuous high speed" to R.string.camera_value_continuous_high_speed,
    "high speed" to R.string.camera_value_continuous_high_speed,
    "continuous low speed" to R.string.camera_value_continuous_low_speed,
    "single: silent shooting" to R.string.camera_value_single_silent_shooting,
    "continuous timer" to R.string.camera_value_continuous_timer,
    "timer" to R.string.camera_value_timer,
    "timer 10 sec" to R.string.camera_value_timer_10_seconds,
    "timer 2 sec" to R.string.camera_value_timer_2_seconds,
    "timelapse" to R.string.camera_value_timelapse,
    "super high speed continuous shooting" to R.string.camera_value_super_high_speed_continuous,
    "single silent" to R.string.camera_value_single_silent_shooting,
    "continuous silent" to R.string.camera_value_continuous_silent,
    "silent hs continuous" to R.string.camera_value_silent_high_speed_continuous,
    "silent ls continuous" to R.string.camera_value_silent_low_speed_continuous,
)

private val meteringModeValueLabels = mapOf(
    "center weighted" to R.string.camera_value_center_weighted_metering,
    "spot" to R.string.camera_value_spot_metering,
    "center spot" to R.string.camera_value_spot_metering,
    "average" to R.string.camera_value_average_metering,
    "evaluative" to R.string.camera_value_evaluative_metering,
    "partial" to R.string.camera_value_partial_metering,
    "center weighted average" to R.string.camera_value_center_weighted_average_metering,
    "spot metering interlocked with af frame" to R.string.camera_value_spot_metering_af_linked,
    "multi spot" to R.string.camera_value_multi_spot_metering,
)

private val flashModeValueLabels = mapOf(
    "auto" to R.string.camera_value_auto,
    "off" to R.string.camera_value_off,
    "fill" to R.string.camera_value_fill_flash,
    "red eye auto" to R.string.camera_value_red_eye_auto,
    "red eye fill" to R.string.camera_value_red_eye_fill,
    "external sync" to R.string.camera_value_external_sync,
)

private val pictureStyleValueLabels = mapOf(
    "auto" to R.string.camera_value_auto,
    "standard" to R.string.camera_value_standard,
    "portrait" to R.string.camera_value_portrait,
    "landscape" to R.string.camera_value_landscape,
    "neutral" to R.string.camera_value_neutral,
    "faithful" to R.string.camera_value_faithful,
    "monochrome" to R.string.camera_value_monochrome,
    "fine detail" to R.string.camera_value_fine_detail,
    "user defined 1" to R.string.camera_value_user_defined_1,
    "user defined 2" to R.string.camera_value_user_defined_2,
    "user defined 3" to R.string.camera_value_user_defined_3,
)

private val highIsoNoiseReductionValueLabels = mapOf(
    "off" to R.string.camera_value_off,
    "low" to R.string.camera_value_low,
    "normal" to R.string.camera_value_normal,
    "high" to R.string.camera_value_high,
    "multi shot" to R.string.camera_value_multi_shot,
)

private val imageQualityValueLabels = mapOf(
    "raw" to R.string.camera_value_raw,
    "craw" to R.string.camera_value_craw,
    "mraw" to R.string.camera_value_mraw,
    "sraw" to R.string.camera_value_sraw,
    "large fine jpeg" to R.string.camera_value_large_fine_jpeg,
    "large normal jpeg" to R.string.camera_value_large_normal_jpeg,
    "smaller jpeg" to R.string.camera_value_smaller_jpeg,
    "craw + large fine jpeg" to R.string.camera_value_craw_large_fine_jpeg,
    "craw + large normal jpeg" to R.string.camera_value_craw_large_normal_jpeg,
    "raw + large fine jpeg" to R.string.camera_value_raw_large_fine_jpeg,
    "raw + large normal jpeg" to R.string.camera_value_raw_large_normal_jpeg,
    "craw + smaller jpeg" to R.string.camera_value_craw_smaller_jpeg,
    "raw + smaller jpeg" to R.string.camera_value_raw_smaller_jpeg,
)

private val shootingModeValueLabels = mapOf(
    "auto" to R.string.camera_value_auto,
    "p" to R.string.camera_value_program_ae,
    "tv" to R.string.camera_value_shutter_priority_ae,
    "av" to R.string.camera_value_aperture_priority_ae,
    "manual" to R.string.camera_value_manual_exposure,
    "bulb" to R.string.camera_value_bulb,
    "a dep" to R.string.camera_value_auto_depth_of_field_ae,
    "dep" to R.string.camera_value_depth_of_field_ae,
    "custom" to R.string.camera_value_custom_shooting,
    "lock" to R.string.camera_value_mode_lock,
    "green" to R.string.camera_value_full_auto,
    "night portrait" to R.string.camera_value_night_portrait,
    "sports" to R.string.camera_value_sports,
    "creative" to R.string.camera_value_creative,
    "landscape" to R.string.camera_value_landscape,
    "closeup" to R.string.camera_value_close_up,
    "flash off" to R.string.camera_value_flash_off,
    "c2" to R.string.camera_value_custom_mode_2,
    "c3" to R.string.camera_value_custom_mode_3,
    "creative auto" to R.string.camera_value_creative_auto,
    "movie" to R.string.camera_value_movie,
    "handheld night scene" to R.string.camera_value_handheld_night_scene,
    "hdr backlight control" to R.string.camera_value_hdr_backlight_control,
    "scn" to R.string.camera_value_special_scene,
    "food" to R.string.camera_value_food,
    "grainy b/w" to R.string.camera_value_grainy_black_white,
    "soft focus" to R.string.camera_value_soft_focus,
    "toy camera effect" to R.string.camera_value_toy_camera_effect,
    "fish eye effect" to R.string.camera_value_fish_eye_effect,
    "water painting effect" to R.string.camera_value_water_painting_effect,
    "miniature effect" to R.string.camera_value_miniature_effect,
    "hdr art standard" to R.string.camera_value_hdr_art_standard,
    "hdr art vivid" to R.string.camera_value_hdr_art_vivid,
    "hdr art bold" to R.string.camera_value_hdr_art_bold,
    "hdr art embossed" to R.string.camera_value_hdr_art_embossed,
    "panning" to R.string.camera_value_panning,
    "hdr" to R.string.camera_value_hdr,
    "self portrait" to R.string.camera_value_self_portrait,
    "hybrid auto" to R.string.camera_value_hybrid_auto,
    "smooth skin" to R.string.camera_value_smooth_skin,
    "fv" to R.string.camera_value_flexible_priority_ae,
    "action" to R.string.camera_value_action,
    "portrait" to R.string.camera_value_portrait,
)

private val WHITESPACE = Regex("\\s+")
