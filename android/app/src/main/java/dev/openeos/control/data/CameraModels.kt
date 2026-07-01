package dev.openeos.control.data

data class CameraInfo(
    val connected: Boolean,
    val model: String,
    val serial: String,
    val api: String,
)

data class ExposureState(
    val iso: String,
    val shutter: String,
    val aperture: String,
    val whiteBalance: String,
)

data class CameraStatus(
    val connected: Boolean,
    val batteryLevel: Int,
    val batteryStatus: String,
    val recording: Boolean,
    val mode: String,
    val mediaAvailable: Boolean,
    val remainingMinutes: Int,
    val exposure: ExposureState,
    val rawBatteryJson: String = "",
    val rawStorageJson: String = "",
)

data class CameraCapabilities(
    val iso: List<String>,
    val shutter: List<String>,
    val aperture: List<String>,
    val whiteBalance: List<String>,
)

data class LiveViewFrame(
    val bytes: ByteArray,
    val contentType: String?,
    val sourceUrl: String,
)

data class FocusResult(
    val ok: Boolean,
    val x: Double,
    val y: Double,
)
