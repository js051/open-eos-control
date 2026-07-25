package dev.openeos.control.ui

import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraTransport
import java.net.URI
import java.util.Locale

fun rollingFps(frameTimesMillis: List<Long>): Double {
    if (frameTimesMillis.size < 2) return 0.0
    val elapsed = frameTimesMillis.last() - frameTimesMillis.first()
    if (elapsed <= 0L) return 0.0
    return (frameTimesMillis.size - 1) * 1_000.0 / elapsed
}

fun buildDiagnosticReport(state: CameraUiState): String {
    val activeBaseUrl = when (state.transport) {
        CameraTransport.DESKTOP_BRIDGE -> state.bridgeBaseUrl
        else -> state.baseUrl
    }
    val safeUrl = if (state.transport == CameraTransport.USB_PTP) {
        "not-applicable"
    } else {
        runCatching {
            val uri = URI(activeBaseUrl)
            URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
        }.getOrDefault(redactDiagnosticText(activeBaseUrl, state))
    }
    val supported = state.capabilities?.matrix?.supported.orEmpty()
        .sortedBy(CameraFeature::name)
        .joinToString { it.name }
    val planned = state.capabilities?.matrix?.planned.orEmpty()
        .sortedBy(CameraFeature::name)
        .joinToString { it.name }
    val live = state.liveViewDiagnostics
    val network = state.networkDiagnostics
    val evidence = state.capabilities?.evidence

    return buildString {
        appendLine("Open EOS Control diagnostic report")
        appendLine("camera=${state.info?.model ?: "unknown"}")
        appendLine("serial=${state.info?.serial ?: "unknown"}")
        appendLine("transport=${if (state.previewMode) "OFFLINE_PREVIEW" else state.transport?.name ?: "disconnected"}")
        appendLine("baseUrl=$safeUrl")
        appendLine("api=${state.info?.api ?: "unknown"}")
        appendLine("manufacturer=${state.info?.manufacturer ?: "unknown"}")
        appendLine("deviceVersion=${state.info?.deviceVersion ?: "unknown"}")
        appendLine("engineVersion=${state.info?.engineVersion ?: "unknown"}")
        appendLine("cameraRoute=${network.routing.name}")
        appendLine("cameraNetworkHandle=${network.networkHandle ?: "none"}")
        appendLine("cameraInterface=${network.interfaceName ?: "none"}")
        appendLine("cameraNetworkAvailable=${network.cameraNetworkAvailable}")
        appendLine("wifiAvailable=${network.wifiAvailable}")
        appendLine("cellularAvailable=${network.cellularAvailable}")
        appendLine("cellularValidated=${network.cellularValidated}")
        appendLine("systemDefaultTransport=${network.systemDefaultTransport.name}")
        appendLine("systemDefaultValidated=${network.systemDefaultValidated}")
        appendLine("systemDefaultNetworkHandle=${network.systemDefaultNetworkHandle ?: "none"}")
        appendLine("systemDefaultInterface=${network.systemDefaultInterfaceName ?: "none"}")
        appendLine("wifiCellularCoexistence=${network.wifiCellularCoexistence}")
        appendLine("supported=$supported")
        appendLine("planned=$planned")
        appendLine("capabilitySource=${evidence?.source?.let { redactDiagnosticText(it, state) } ?: "unknown"}")
        appendLine("protocolVersions=${evidence?.protocolVersions.orEmpty().joinToString().ifBlank { "none" }}")
        appendLine("advertisedCommandCount=${evidence?.advertisedCommands?.size ?: 0}")
        appendLine(
            "advertisedCommands=${evidence?.advertisedCommands.orEmpty().joinToString(" | ") { redactDiagnosticText(it, state) }.ifBlank { "none" }}"
        )
        appendLine("writableSettings=${evidence?.writableSettings.orEmpty().joinToString().ifBlank { "none" }}")
        appendLine(
            "observedFeatures=${evidence?.observedFeatures.orEmpty().sortedBy(CameraFeature::name).joinToString { it.name }.ifBlank { "none" }}"
        )
        appendLine("capabilityEvidenceTruncated=${evidence?.truncated ?: false}")
        appendLine("battery=${state.status?.rawBatteryJson?.ifBlank { state.status?.batteryStatus } ?: "unknown"}")
        appendLine("storage=${state.status?.rawStorageJson?.ifBlank { state.status?.mediaAvailable?.toString() } ?: "unknown"}")
        appendLine("storageAvailable=${state.status?.mediaAvailable ?: "unknown"}")
        appendLine("storageTotalBytes=${state.status?.storageTotalBytes ?: "unknown"}")
        appendLine("storageFreeBytes=${state.status?.storageFreeBytes ?: "unknown"}")
        appendLine("storageFreeImages=${state.status?.storageFreeImages ?: "unknown"}")
        appendLine("storageDevices=${state.status?.storageDeviceCount ?: "unknown"}")
        appendLine(
            "transportDetails=${state.status?.rawTransportJson?.ifBlank { "unknown" }?.let { redactDiagnosticText(it, state) } ?: "unknown"}"
        )
        appendLine("requestedFps=${state.liveViewFrameRateFps}")
        appendLine("liveViewSource=${state.liveViewSource.name}")
        appendLine("observedFps=${String.format(Locale.US, "%.1f", live.observedFps)}")
        appendLine("frameBytes=${live.frameBytes ?: "unknown"}")
        appendLine("contentType=${live.contentType ?: "unknown"}")
        appendLine("source=${live.sourceUrl?.let { redactDiagnosticText(it, state) } ?: "unknown"}")
        appendLine("lastFrameAtMillis=${live.lastFrameAtMillis ?: "unknown"}")
        appendLine("liveViewHealthy=${live.lastFrameAtMillis != null}")
        appendLine("usbDevices=${state.usbDiagnostics.devices.size}")
        append("lastError=${state.error?.let { redactDiagnosticText(it, state) } ?: "none"}")
    }
}

private fun redactDiagnosticText(value: String, state: CameraUiState): String {
    var redacted = value
        .replace(Regex("(?i)(authorization\\s*[:=]\\s*)([^\\r\\n,]+)"), "\$1[redacted]")
        .replace(Regex("(https?://)[^/@\\s]+@"), "\$1")
    if (state.password.isNotBlank()) redacted = redacted.replace(state.password, "[redacted]")
    if (state.bridgeToken.isNotBlank()) redacted = redacted.replace(state.bridgeToken, "[redacted]")
    return redacted
}
