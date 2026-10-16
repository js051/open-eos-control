package dev.openeos.control.ui

import dev.openeos.control.data.*

internal fun CameraUiState.shootingSettingControl(key: String): CameraSettingControl? {
    val capabilities = capabilities ?: return null
    val values = when (key) {
        "iso" -> capabilities.iso
        "shutter" -> capabilities.shutter
        "aperture" -> capabilities.aperture
        "whitebalance" -> capabilities.whiteBalance
        else -> return capabilities.advancedSettings.singleOrNull { it.key == key }
    }
    val feature = if (key == "whitebalance") CameraFeature.WHITE_BALANCE_CONTROL else CameraFeature.EXPOSURE_CONTROL
    if (!supports(feature) || values.isEmpty()) return null
    val current = when (key) {
        "iso" -> status?.exposure?.iso
        "shutter" -> status?.exposure?.shutter
        "aperture" -> status?.exposure?.aperture
        else -> status?.exposure?.whiteBalance
    } ?: return null
    return CameraSettingControl(key, key, current, values)
}

/** Only the freshly read selected field changes; a setting read is not a status/safety snapshot. */
internal fun CameraUiState.withShootingSettingObservation(observed: ShootingSettingObservation): CameraUiState {
    val exposure = status?.exposure
    val updated = when (observed.key) {
        "iso" -> exposure?.copy(iso = observed.value)
        "shutter" -> exposure?.copy(shutter = observed.value)
        "aperture" -> exposure?.copy(aperture = observed.value)
        "whitebalance" -> exposure?.copy(whiteBalance = observed.value)
        else -> exposure
    }
    return copy(status = if (updated == null) status else status?.copy(exposure = updated),
        capabilities = capabilities?.copy(advancedSettings = capabilities.advancedSettings.map {
            if (it.key == observed.key) it.copy(value = observed.value) else it
        }))
}
