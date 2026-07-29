package dev.openeos.control.ui

import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraTransport
import java.net.URI
import java.time.Instant
import java.util.Locale

private const val DIAGNOSTIC_REPORT_SCHEMA = 1

data class DiagnosticValidationSummary(
    val advertisedFeatures: Set<CameraFeature>,
    val observedFeatures: Set<CameraFeature>,
) {
    val validatedAdvertisedFeatures: Set<CameraFeature> = advertisedFeatures intersect observedFeatures
    val unverifiedAdvertisedFeatures: Set<CameraFeature> = advertisedFeatures - observedFeatures
    val observedWithoutAdvertisement: Set<CameraFeature> = observedFeatures - advertisedFeatures
}

data class DiagnosticReportMetadata(
    val productVersion: String = "unknown",
    val generatedAt: String = Instant.now().toString(),
)

fun rollingFps(frameTimesMillis: List<Long>): Double {
    if (frameTimesMillis.size < 2) return 0.0
    val elapsed = frameTimesMillis.last() - frameTimesMillis.first()
    if (elapsed <= 0L) return 0.0
    return (frameTimesMillis.size - 1) * 1_000.0 / elapsed
}

fun diagnosticValidationSummary(state: CameraUiState): DiagnosticValidationSummary = DiagnosticValidationSummary(
    advertisedFeatures = state.capabilities?.matrix?.supported.orEmpty(),
    observedFeatures = state.capabilities?.evidence?.observedFeatures.orEmpty(),
)

fun buildDiagnosticReport(
    state: CameraUiState,
    metadata: DiagnosticReportMetadata = DiagnosticReportMetadata(),
): String {
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
    val validation = diagnosticValidationSummary(state)

    val report = buildString {
        appendLine("Open EOS Control diagnostic report")
        appendLine("reportSchema=$DIAGNOSTIC_REPORT_SCHEMA")
        appendLine("generatedAt=${metadata.generatedAt}")
        appendLine("productVersion=${metadata.productVersion}")
        appendLine("camera=${state.info?.model ?: "unknown"}")
        appendLine("serial=${diagnosticSerial(state.info?.serial)}")
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
        appendLine("advertisedFeatureCount=${validation.advertisedFeatures.size}")
        appendLine("observedFeatureCount=${validation.observedFeatures.size}")
        appendLine("validatedAdvertisedFeatureCount=${validation.validatedAdvertisedFeatures.size}")
        appendLine(
            "unverifiedAdvertisedFeatures=${validation.unverifiedAdvertisedFeatures.sortedBy(CameraFeature::name).joinToString { it.name }.ifBlank { "none" }}"
        )
        appendLine(
            "observedWithoutAdvertisement=${validation.observedWithoutAdvertisement.sortedBy(CameraFeature::name).joinToString { it.name }.ifBlank { "none" }}"
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
        appendLine("monitorHistogram=${state.monitorSettings.histogramVisible}")
        appendLine("monitorZebra=${state.monitorSettings.zebraThresholdPercent ?: "off"}")
        appendLine("monitorFalseColor=${state.monitorSettings.falseColorEnabled}")
        appendLine("monitorFocusPeaking=${state.monitorSettings.focusPeakingEnabled}")
        appendLine("monitorFrameGuide=${state.monitorSettings.frameGuide.name}")
        appendLine("monitorSafeArea=${state.monitorSettings.safeAreaVisible}")
        appendLine("monitorDesqueeze=${state.monitorSettings.desqueeze.name}")
        appendLine("observedFps=${String.format(Locale.US, "%.1f", live.observedFps)}")
        appendLine("frameBytes=${live.frameBytes ?: "unknown"}")
        appendLine("contentType=${live.contentType ?: "unknown"}")
        appendLine("source=${live.sourceUrl?.let { redactDiagnosticText(it, state) } ?: "unknown"}")
        appendLine("lastFrameAtMillis=${live.lastFrameAtMillis ?: "unknown"}")
        appendLine("liveViewHealthy=${live.lastFrameAtMillis != null}")
        appendLine("usbDevices=${state.usbDiagnostics.devices.size}")
        append("lastError=${state.error?.let { redactDiagnosticText(it, state) } ?: "none"}")
    }
    return redactDiagnosticText(report, state)
}

private fun redactDiagnosticText(value: String, state: CameraUiState): String {
    var redacted = value
        .replace(Regex("(?i)(authorization\\s*[:=]\\s*)([^\\r\\n,]+)"), "\$1[redacted]")
        .replace(Regex("(https?://)[^/@\\s]+@"), "\$1")
        .replace(Regex("(?i)\\b[A-Z]:[\\\\/]+[^\\r\\n,;\"'}\\]]+"), "[local-path]")
        .replace(Regex("(?i)\\\\\\\\[^\\\\\\r\\n\\s\"']+\\\\[^\\r\\n,;\"'}\\]]+"), "[local-path]")
        .replace(Regex("(?i)\\bfile://[^\\r\\n,;\"'}\\]]+"), "[local-path]")
        .replace(
            Regex(
                "(?<![A-Za-z0-9_])/(?:Users|home|tmp|var/folders|private/var|data/user|storage/emulated|mnt/[a-z])/" +
                    "[^\\r\\n,;\"'}\\]]+",
            ),
            "[local-path]",
        )
    if (state.password.isNotBlank()) redacted = redacted.replace(state.password, "[redacted]")
    if (state.bridgeToken.isNotBlank()) redacted = redacted.replace(state.bridgeToken, "[redacted]")
    state.info?.serial?.takeUnless { it.isDiagnosticUnknown() }?.let { serial ->
        redacted = redacted.replace(serial, "[redacted]")
    }
    return redacted
}

private fun diagnosticSerial(serial: String?): String = when {
    serial.isDiagnosticUnknown() -> "unknown"
    else -> "[redacted]"
}

private fun String?.isDiagnosticUnknown(): Boolean =
    isNullOrBlank() || equals("unknown", ignoreCase = true) || equals("none", ignoreCase = true)
