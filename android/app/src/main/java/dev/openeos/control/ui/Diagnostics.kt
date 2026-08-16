package dev.openeos.control.ui

import dev.openeos.control.data.CameraDiscoveryAttempt
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraTransport
import dev.openeos.control.data.NativeLiveViewVideoStatus
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

private const val DIAGNOSTIC_REPORT_SCHEMA = 1
private const val PHYSICAL_VALIDATION_RECORD_SCHEMA = 1

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
    val systemAutoRotationEnabled: Boolean? = null,
    val controlRotationDegrees: Float? = null,
)

enum class PhysicalValidationSessionStatus {
    READY,
    DISCONNECTED,
    OFFLINE_PREVIEW,
    SIMULATOR,
}

data class PhysicalValidationSummary(
    val sessionStatus: PhysicalValidationSessionStatus,
    val advertisedFeatures: Set<CameraFeature>,
    val observedFeatures: Set<CameraFeature>,
    val eligibleFeatures: Set<CameraFeature>,
    val operatorConfirmedFeatures: Set<CameraFeature>,
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

fun physicalValidationSummary(state: CameraUiState): PhysicalValidationSummary {
    val validation = diagnosticValidationSummary(state)
    val status = when {
        !state.connected -> PhysicalValidationSessionStatus.DISCONNECTED
        state.previewMode -> PhysicalValidationSessionStatus.OFFLINE_PREVIEW
        state.isSimulatorSession() -> PhysicalValidationSessionStatus.SIMULATOR
        else -> PhysicalValidationSessionStatus.READY
    }
    val eligible = if (status == PhysicalValidationSessionStatus.READY) {
        validation.validatedAdvertisedFeatures
    } else {
        emptySet()
    }
    return PhysicalValidationSummary(
        sessionStatus = status,
        advertisedFeatures = validation.advertisedFeatures,
        observedFeatures = validation.observedFeatures,
        eligibleFeatures = eligible,
        operatorConfirmedFeatures = state.operatorConfirmedFeatures intersect eligible,
    )
}

fun buildPhysicalValidationRecord(
    state: CameraUiState,
    metadata: DiagnosticReportMetadata = DiagnosticReportMetadata(),
): String {
    val validation = physicalValidationSummary(state)
    require(validation.sessionStatus == PhysicalValidationSessionStatus.READY) {
        "A physical camera session is required to create a validation record."
    }
    val diagnosticHash = buildDiagnosticReport(state, metadata)
        .replace("\r\n", "\n")
        .sha256()
    val features = (validation.advertisedFeatures + validation.observedFeatures)
        .sortedBy(CameraFeature::name)

    return buildString {
        appendLine("# Open EOS Control physical camera validation")
        appendLine()
        appendLine("- Record schema: $PHYSICAL_VALIDATION_RECORD_SCHEMA")
        appendLine("- Generated at: ${metadata.generatedAt.markdownCell()}")
        appendLine("- App version: ${metadata.productVersion.markdownCell()}")
        appendLine("- Camera model: ${(state.info?.model ?: "unknown").markdownCell()}")
        appendLine("- Transport: ${(state.transport?.name ?: "unknown").markdownCell()}")
        appendLine("- Diagnostic SHA-256: `$diagnosticHash`")
        appendLine()
        appendLine("Operator confirmation is a manual in-app attestation that the physical camera visibly performed the operation.")
        appendLine()
        appendLine("| Feature | Advertised | Observed this session | Operator confirmed |")
        appendLine("| --- | --- | --- | --- |")
        features.forEach { feature ->
            appendLine(
                "| ${feature.name} | ${feature in validation.advertisedFeatures} | " +
                    "${feature in validation.observedFeatures} | ${feature in validation.operatorConfirmedFeatures} |"
            )
        }
    }.trimEnd()
}

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
        appendLine("controlOrientationMode=FOLLOW_SYSTEM")
        appendLine("systemAutoRotationEnabled=${metadata.systemAutoRotationEnabled ?: "unknown"}")
        appendLine("controlRotationDegrees=${metadata.controlRotationDegrees ?: "unknown"}")
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
        val discoveryTrace = evidence?.discoveryTrace.orEmpty()
        appendLine("discoveryAttemptCount=${discoveryTrace.size}")
        discoveryTrace.forEachIndexed { index, attempt ->
            appendLine("discoveryAttempt${index + 1}=${diagnosticDiscoveryAttempt(attempt)}")
        }
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
        appendLine("recordableShots=${state.status?.recordableShots ?: "unknown"}")
        appendLine("remainingRecordingSeconds=${state.status?.remainingRecordingSeconds ?: "unknown"}")
        appendLine("recordable=${state.status?.rawRecordableJson?.ifBlank { "unknown" } ?: "unknown"}")
        appendLine("mediaItemCount=${state.mediaItems.size}")
        appendLine("mediaLibraryScope=${state.mediaLibraryScope.name}")
        appendLine("mediaLibraryHasMore=${state.mediaLibraryHasMore}")
        appendLine("mediaLoadStatus=${state.mediaLibraryLoadStatus.name}")
        appendLine("lensMounted=${state.status?.lens?.mounted ?: "unknown"}")
        appendLine("lensName=${state.status?.lens?.name?.ifBlank { "none" } ?: "unknown"}")
        appendLine("temperature=${state.status?.temperature?.ccapiValue ?: "unknown"}")
        appendLine("lastClockSyncAtMillis=${state.lastClockSyncAtMillis ?: "none"}")
        appendLine(
            "transportDetails=${state.status?.rawTransportJson?.ifBlank { "unknown" }?.let { redactDiagnosticText(it, state) } ?: "unknown"}"
        )
        appendLine("requestedFps=${state.liveViewFrameRateFps}")
        appendLine("liveViewSource=${state.liveViewSource.name}")
        appendLine("monitorHistogram=${state.monitorSettings.histogramVisible}")
        appendLine("monitorWaveform=${state.monitorSettings.waveformVisible}")
        appendLine("monitorZebra=${state.monitorSettings.zebraThresholdPercent ?: "off"}")
        appendLine("monitorFalseColor=${state.monitorSettings.falseColorEnabled}")
        appendLine("monitorFocusPeaking=${state.monitorSettings.focusPeakingEnabled}")
        appendLine("monitorFrameGuide=${state.monitorSettings.frameGuide.name}")
        appendLine("monitorSafeArea=${state.monitorSettings.safeAreaVisible}")
        appendLine("monitorDesqueeze=${state.monitorSettings.desqueeze.name}")
        appendLine(
            "monitorLut=${state.monitorSettings.cubeLut?.let { "loaded (${it.size}x${it.size}x${it.size})" } ?: "off"}"
        )
        val nativeLiveView = state.nativeLiveViewSession
        appendLine("observedFps=${String.format(Locale.US, "%.1f", live.observedFps)}")
        appendLine("frameBytes=${live.frameBytes ?: "unknown"}")
        appendLine("contentType=${live.contentType ?: nativeLiveView?.contentType ?: "unknown"}")
        appendLine(
            "source=${(live.sourceUrl ?: nativeLiveView?.sourceUrl)?.let { redactDiagnosticText(it, state) } ?: "unknown"}"
        )
        appendLine("lastFrameAtMillis=${live.lastFrameAtMillis ?: "unknown"}")
        appendLine("liveViewHealthy=${live.lastFrameAtMillis != null}")
        val video = nativeLiveView?.videoStatus ?: NativeLiveViewVideoStatus.None
        appendLine("rtpVideoPort=${video.rtpPort ?: "none"}")
        appendLine("rtpVideoDatagrams=${video.datagramsReceived}")
        appendLine("rtpVideoAccessUnits=${video.accessUnitsReceived}")
        appendLine("rtpVideoKeyFrames=${video.keyFramesReceived}")
        appendLine("rtpVideoLastDatagramAtMillis=${video.lastDatagramAtMillis ?: "none"}")
        appendLine("rtpVideoLastAccessUnitAtMillis=${video.lastAccessUnitAtMillis ?: "none"}")
        appendLine("rtpVideoHasSps=${video.hasSequenceParameterSet}")
        appendLine("rtpVideoHasPps=${video.hasPictureParameterSet}")
        appendLine("rtpVideoReady=${video.ready}")
        appendLine("rtpVideoError=${video.error ?: "none"}")
        val audio = state.liveViewAudioStatus
        appendLine("rtpAudioAdvertised=${audio.advertised}")
        appendLine("rtpAudioAvailable=${audio.available}")
        appendLine("rtpAudioEnabled=${audio.enabled}")
        appendLine("rtpAudioCodec=${audio.codec ?: "none"}")
        appendLine("rtpAudioPort=${audio.rtpPort ?: "none"}")
        appendLine("rtpAudioClockRate=${audio.rtpClockRate ?: "none"}")
        appendLine("rtpAudioSampleRate=${audio.sampleRate ?: "none"}")
        appendLine("rtpAudioChannels=${audio.channels ?: "none"}")
        appendLine("rtpAudioPackets=${audio.packetsReceived}")
        appendLine("rtpAudioAccessUnits=${audio.accessUnitsReceived}")
        appendLine("rtpAudioDecodedAccessUnits=${audio.decodedAccessUnits}")
        appendLine("rtpAudioPlayedSampleFrames=${audio.playedSampleFrames}")
        appendLine("rtpAudioDroppedAccessUnits=${audio.droppedAccessUnits}")
        appendLine("rtpAudioUnderruns=${audio.underruns}")
        appendLine("rtpAudioLastPacketAtMillis=${audio.lastPacketAtMillis ?: "none"}")
        appendLine("rtpAudioLastPcmAtMillis=${audio.lastPcmAtMillis ?: "none"}")
        appendLine("rtpAudioError=${audio.error ?: "none"}")
        appendLine("usbDevices=${state.usbDiagnostics.devices.size}")
        append("lastError=${state.error?.let { redactDiagnosticText(it, state) } ?: "none"}")
    }
    return redactDiagnosticText(report, state)
}

fun diagnosticDiscoveryAttempt(attempt: CameraDiscoveryAttempt): String = buildString {
    append("endpoint=${attempt.endpoint}")
    append("; outcome=${attempt.outcome}")
    append("; httpStatus=${attempt.httpStatus ?: "none"}")
    append("; responseKeys=${attempt.responseKeys.joinToString().ifBlank { "none" }}")
    append("; protocolVersions=${attempt.protocolVersions.joinToString().ifBlank { "none" }}")
    append("; advertisedOperationCount=${attempt.advertisedOperationCount}")
    append("; truncated=${attempt.truncated}")
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

private fun CameraUiState.isSimulatorSession(): Boolean = sequenceOf(
    info?.api,
    info?.model,
    capabilities?.evidence?.source,
).filterNotNull().any { it.contains("simulat", ignoreCase = true) }

private fun String.markdownCell(): String = replace('\r', ' ')
    .replace('\n', ' ')
    .replace("|", "\\|")
    .take(160)

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
