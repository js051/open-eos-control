package dev.openeos.control.data

internal class DesktopBridgeException(val statusCode: Int, val code: String = "HTTP_$statusCode") : IllegalStateException("Synthetic HTTP $statusCode")
internal class AutofocusReleaseException(cause: Throwable) : IllegalStateException("Synthetic focus release uncertainty", cause)
internal class MaintenanceRetirementUnconfirmedException(val retirementUnconfirmed: Boolean,
    val physicalReleaseUnconfirmed: Boolean, val stopUnconfirmed: Boolean) : IllegalStateException("Synthetic retirement uncertainty")

/** Explicit host-only adapters for otherwise unrelated Android model dependencies. */
data class ExposureState(val iso: String, val shutter: String, val aperture: String, val whiteBalance: String)
data class CameraStatus(
    val connected: Boolean = true,
    val batteryLevel: Int? = null,
    val batteryStatus: String = "synthetic",
    val recording: Boolean? = false,
    val mode: String = "photo",
    val mediaAvailable: Boolean? = true,
    val remainingMinutes: Int? = null,
    val exposure: ExposureState = ExposureState("100", "bulb", "4.0", "auto"),
    val bulbExposureActive: Boolean? = null,
    val shutterReleaseUnconfirmed: Boolean? = null,
    val liveViewStopUnconfirmed: Boolean? = null,
    val heldAutofocusActive: Boolean? = null,
)
