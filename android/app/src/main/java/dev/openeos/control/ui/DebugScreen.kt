package dev.openeos.control.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.openeos.control.R
import com.composables.icons.lucide.R as LucideR
import dev.openeos.control.data.CameraNetworkRouting
import dev.openeos.control.data.CameraTransport
import dev.openeos.control.data.SystemNetworkTransport
import dev.openeos.control.data.UsbDiagnosticState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DebugScreen(
    state: CameraUiState,
    actions: CameraActions,
    systemAutoRotationEnabled: Boolean = false,
    controlRotationDegrees: Float = 0f,
) {
    val context = LocalContext.current
    val unknown = stringResource(R.string.unknown)
    val none = stringResource(R.string.none)
    val unavailable = stringResource(R.string.unavailable)
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        CameraHeader(state, actions)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.debug), color = AppText, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            ToolIconButton(LucideR.drawable.lucide_ic_languages, stringResource(R.string.language), { actions.openPicker(SettingPicker.LANGUAGE) })
            ToolIconButton(LucideR.drawable.lucide_ic_refresh_cw, stringResource(R.string.refresh), actions.refresh, enabled = !state.isBusy(CameraOperation.STATUS))
            ToolIconButton(LucideR.drawable.lucide_ic_rotate_ccw, stringResource(R.string.restart_live_view), actions.restartLiveView, enabled = !state.isBusy(CameraOperation.LIVE_VIEW))
            ToolIconButton(LucideR.drawable.lucide_ic_usb, stringResource(R.string.usb_scan), actions.refreshUsb, enabled = !state.isBusy(CameraOperation.USB))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            DebugSection(stringResource(R.string.overview)) {
                DebugValue(stringResource(R.string.camera_profile), state.capabilities?.profile?.modelName ?: unknown)
                DebugValue(
                    stringResource(R.string.transport),
                    if (state.previewMode) stringResource(R.string.offline_preview) else state.transport?.let { transportLabel(it) } ?: unknown,
                )
                DebugValue(stringResource(R.string.api_version), state.info?.api ?: unknown)
                DebugValue(stringResource(R.string.manufacturer), state.info?.manufacturer ?: unavailable)
                DebugValue(stringResource(R.string.device_version), state.info?.deviceVersion ?: unavailable)
                DebugValue(stringResource(R.string.engine_version), state.info?.engineVersion ?: unavailable)
                DebugValue(stringResource(R.string.last_error), state.error ?: none, warning = state.error != null)
            }
            DebugSection(stringResource(R.string.ccapi)) {
                DebugValue(stringResource(R.string.supported_features), state.capabilities?.matrix?.supported.orEmpty().joinToString { it.name }.ifBlank { none })
                DebugValue(stringResource(R.string.planned_features), state.capabilities?.matrix?.planned.orEmpty().joinToString { it.name }.ifBlank { none })
                DebugValue(
                    stringResource(R.string.storage_available),
                    state.status?.mediaAvailable?.let { yesNoLabel(it) } ?: unknown,
                )
                DebugValue(stringResource(R.string.storage_total_bytes), state.status?.storageTotalBytes?.toString() ?: unavailable, mono = true)
                DebugValue(stringResource(R.string.storage_free_bytes), state.status?.storageFreeBytes?.toString() ?: unavailable, mono = true)
                DebugValue(stringResource(R.string.storage_free_images), state.status?.storageFreeImages?.toString() ?: unavailable, mono = true)
                DebugValue(stringResource(R.string.storage_devices), state.status?.storageDeviceCount?.toString() ?: unavailable, mono = true)
                DebugValue(stringResource(R.string.recordable_shots), state.status?.recordableShots?.toString() ?: unavailable, mono = true)
                DebugValue(stringResource(R.string.remaining_recording_time), state.status?.remainingRecordingSeconds?.let(::formatRecordingDuration) ?: unavailable, mono = true)
                DebugValue(stringResource(R.string.battery_raw), state.status?.rawBatteryJson?.ifBlank { unavailable } ?: unavailable, mono = true)
                DebugValue(stringResource(R.string.storage_raw), state.status?.rawStorageJson?.ifBlank { unavailable } ?: unavailable, mono = true)
                DebugValue(stringResource(R.string.recordable_raw), state.status?.rawRecordableJson?.ifBlank { unavailable } ?: unavailable, mono = true)
                DebugValue(
                    stringResource(R.string.lens),
                    state.status?.lens?.let { lens ->
                        if (lens.mounted) lens.name else stringResource(R.string.no_lens_mounted)
                    } ?: unavailable,
                )
                DebugValue(
                    stringResource(R.string.temperature_status),
                    state.status?.temperature?.ccapiValue ?: unavailable,
                    mono = true,
                    warning = state.status?.temperature?.isNormal == false,
                )
            }
            DebugSection(stringResource(R.string.capability_evidence)) {
                val evidence = state.capabilities?.evidence
                val validation = diagnosticValidationSummary(state)
                DebugValue(stringResource(R.string.capability_source), evidence?.source ?: unknown, mono = true)
                DebugValue(
                    stringResource(R.string.protocol_versions),
                    evidence?.protocolVersions.orEmpty().joinToString().ifBlank { none },
                    mono = true,
                )
                DebugValue(
                    stringResource(R.string.advertised_commands),
                    evidence?.advertisedCommands.orEmpty().joinToString("\n").ifBlank { none },
                    mono = true,
                )
                DebugValue(
                    stringResource(R.string.writable_settings),
                    evidence?.writableSettings.orEmpty().joinToString().ifBlank { none },
                    mono = true,
                )
                DebugValue(
                    stringResource(R.string.observed_features),
                    evidence?.observedFeatures.orEmpty().sortedBy { it.name }.joinToString { it.name }.ifBlank { none },
                    mono = true,
                )
                DebugValue(
                    stringResource(R.string.validation_coverage),
                    stringResource(
                        R.string.validation_coverage_value,
                        validation.validatedAdvertisedFeatures.size,
                        validation.advertisedFeatures.size,
                    ),
                )
                DebugValue(
                    stringResource(R.string.unverified_advertised_features),
                    validation.unverifiedAdvertisedFeatures.sortedBy { it.name }
                        .joinToString { it.name }
                        .ifBlank { none },
                    mono = true,
                    warning = validation.unverifiedAdvertisedFeatures.isNotEmpty(),
                )
                DebugValue(
                    stringResource(R.string.observed_without_advertisement),
                    validation.observedWithoutAdvertisement.sortedBy { it.name }
                        .joinToString { it.name }
                        .ifBlank { none },
                    mono = true,
                    warning = validation.observedWithoutAdvertisement.isNotEmpty(),
                )
                DebugValue(
                    stringResource(R.string.evidence_truncated),
                    yesNoLabel(evidence?.truncated == true),
                    warning = evidence?.truncated == true,
                )
            }
            DebugSection(stringResource(R.string.physical_validation)) {
                val validation = physicalValidationSummary(state)
                Text(
                    stringResource(R.string.physical_validation_hint),
                    color = AppSubtleText,
                )
                when (validation.sessionStatus) {
                    PhysicalValidationSessionStatus.OFFLINE_PREVIEW -> Text(
                        stringResource(R.string.physical_validation_offline_unavailable),
                        color = AppWarning,
                    )
                    PhysicalValidationSessionStatus.SIMULATOR -> Text(
                        stringResource(R.string.physical_validation_simulator_unavailable),
                        color = AppWarning,
                    )
                    PhysicalValidationSessionStatus.DISCONNECTED -> Text(
                        stringResource(R.string.physical_validation_disconnected),
                        color = AppWarning,
                    )
                    PhysicalValidationSessionStatus.READY -> {
                        if (validation.eligibleFeatures.isEmpty()) {
                            Text(stringResource(R.string.physical_validation_no_observed), color = AppSubtleText)
                        }
                        validation.eligibleFeatures.sortedBy { it.name }.forEach { feature ->
                            val checked = feature in validation.operatorConfirmedFeatures
                            val confirmationDescription = stringResource(
                                R.string.physical_validation_confirmation_description,
                                feature.name,
                            )
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("physical-confirmation-${feature.name}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(feature.name, color = AppText, fontFamily = FontFamily.Monospace)
                                    Text(
                                        stringResource(
                                            if (checked) R.string.physical_validation_confirmed
                                            else R.string.physical_validation_not_confirmed,
                                        ),
                                        color = if (checked) AppSuccess else AppSubtleText,
                                    )
                                }
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { actions.setOperatorConfirmation(feature, it) },
                                    modifier = Modifier.semantics { contentDescription = confirmationDescription },
                                )
                            }
                        }
                    }
                }
                Button(
                    onClick = {
                        val metadata = diagnosticMetadata(
                            context = context,
                            systemAutoRotationEnabled = systemAutoRotationEnabled,
                            controlRotationDegrees = controlRotationDegrees,
                        )
                        val record = buildPhysicalValidationRecord(state, metadata)
                        copyToClipboard(context, "Open EOS Control physical validation", record)
                        Toast.makeText(
                            context,
                            context.getString(R.string.physical_validation_copied),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    enabled = validation.sessionStatus == PhysicalValidationSessionStatus.READY &&
                        validation.eligibleFeatures.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().testTag("copy-physical-validation-record"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppSurfaceHigh,
                        contentColor = AppText,
                    ),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_clipboard_check), null)
                    Text(stringResource(R.string.copy_physical_validation), modifier = Modifier.padding(start = 8.dp))
                }
            }
            DebugSection(stringResource(R.string.network)) {
                val network = state.networkDiagnostics
                DebugValue(stringResource(R.string.camera_route), networkRoutingLabel(network.routing))
                DebugValue(stringResource(R.string.target_host), network.targetHost ?: unknown, mono = true)
                DebugValue(stringResource(R.string.network_interface), network.interfaceName ?: unavailable, mono = true)
                DebugValue(stringResource(R.string.network_handle), network.networkHandle?.toString() ?: unavailable, mono = true)
                DebugValue(stringResource(R.string.camera_network_available), yesNoLabel(network.cameraNetworkAvailable))
                DebugValue(stringResource(R.string.wifi_available), yesNoLabel(network.wifiAvailable))
                DebugValue(stringResource(R.string.cellular_available), yesNoLabel(network.cellularAvailable))
                DebugValue(stringResource(R.string.cellular_validated), yesNoLabel(network.cellularValidated))
                DebugValue(
                    stringResource(R.string.system_default_transport),
                    systemNetworkTransportLabel(network.systemDefaultTransport),
                )
                DebugValue(
                    stringResource(R.string.system_default_interface),
                    network.systemDefaultInterfaceName ?: unavailable,
                    mono = true,
                )
                DebugValue(
                    stringResource(R.string.system_default_network_handle),
                    network.systemDefaultNetworkHandle?.toString() ?: unavailable,
                    mono = true,
                )
                DebugValue(
                    stringResource(R.string.system_default_validated),
                    yesNoLabel(network.systemDefaultValidated),
                )
                DebugValue(
                    stringResource(R.string.wifi_cellular_coexistence),
                    yesNoLabel(network.wifiCellularCoexistence),
                )
            }
            DebugSection(stringResource(R.string.live_view)) {
                val live = state.liveViewDiagnostics
                DebugValue(stringResource(R.string.requested_fps), state.liveViewFrameRateFps.toString())
                DebugValue(stringResource(R.string.observed_fps), String.format(Locale.US, "%.1f", live.observedFps))
                DebugValue(stringResource(R.string.frame_bytes), live.frameBytes?.toString() ?: unknown)
                DebugValue(stringResource(R.string.content_type), live.contentType ?: unknown)
                DebugValue(stringResource(R.string.source_endpoint), live.sourceUrl ?: unknown, mono = true)
                DebugValue(
                    stringResource(R.string.latest_frame),
                    live.lastFrameAtMillis?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: unknown,
                )
                val audio = state.liveViewAudioStatus
                DebugValue(stringResource(R.string.rtp_audio_advertised), yesNoLabel(audio.advertised))
                DebugValue(stringResource(R.string.rtp_audio_available), yesNoLabel(audio.available))
                DebugValue(stringResource(R.string.rtp_audio_enabled), yesNoLabel(audio.enabled))
                DebugValue(stringResource(R.string.rtp_audio_codec), audio.codec ?: unavailable, mono = true)
                DebugValue(stringResource(R.string.rtp_audio_port), audio.rtpPort?.toString() ?: unavailable)
                DebugValue(stringResource(R.string.rtp_audio_clock_rate), audio.rtpClockRate?.toString() ?: unavailable)
                DebugValue(stringResource(R.string.rtp_audio_sample_rate), audio.sampleRate?.toString() ?: unavailable)
                DebugValue(stringResource(R.string.rtp_audio_channels), audio.channels?.toString() ?: unavailable)
                DebugValue(stringResource(R.string.rtp_audio_packets), audio.packetsReceived.toString())
                DebugValue(stringResource(R.string.rtp_audio_access_units), audio.accessUnitsReceived.toString())
                DebugValue(stringResource(R.string.rtp_audio_decoded_units), audio.decodedAccessUnits.toString())
                DebugValue(stringResource(R.string.rtp_audio_played_frames), audio.playedSampleFrames.toString())
                DebugValue(stringResource(R.string.rtp_audio_dropped_units), audio.droppedAccessUnits.toString())
                DebugValue(stringResource(R.string.rtp_audio_underruns), audio.underruns.toString())
                DebugValue(
                    stringResource(R.string.rtp_audio_latest_packet),
                    audio.lastPacketAtMillis?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: unavailable,
                )
                DebugValue(
                    stringResource(R.string.rtp_audio_latest_pcm),
                    audio.lastPcmAtMillis?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: unavailable,
                )
                DebugValue(
                    stringResource(R.string.rtp_audio_error),
                    audio.error ?: none,
                    warning = audio.error != null,
                )
            }
            DebugSection(stringResource(R.string.usb_ptp)) {
                DebugValue(
                    stringResource(R.string.transport_raw),
                    state.status?.rawTransportJson?.ifBlank { unavailable } ?: unavailable,
                    mono = true,
                )
                if (state.usbDiagnostics.devices.isEmpty()) Text(stringResource(R.string.no_usb_devices), color = AppSubtleText)
                state.usbDiagnostics.devices.forEach { device ->
                    Column(Modifier.fillMaxWidth().background(AppSurfaceHigh, RoundedCornerShape(6.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(device.displayName, color = AppText, fontWeight = FontWeight.SemiBold)
                        DebugValue(stringResource(R.string.vid_pid), "%04X / %04X".format(device.vendorId, device.productId), mono = true)
                        DebugValue(stringResource(R.string.device), device.deviceName, mono = true)
                        DebugValue(stringResource(R.string.ptp), device.hasPtpInterface.toString())
                        DebugValue(
                            stringResource(if (device.hasPermission) R.string.permission_granted else R.string.permission_needed),
                            usbStateLabel(device.diagnosticState),
                        )
                        device.interfaces.forEach { cameraInterface ->
                            DebugValue(
                                pluralStringResource(
                                    R.plurals.interface_endpoint,
                                    cameraInterface.endpoints.size,
                                    cameraInterface.id,
                                    cameraInterface.endpoints.size,
                                ),
                                cameraInterface.endpoints.joinToString { "${it.direction}/${it.transferType}@${it.address}" }.ifBlank { none },
                                mono = true,
                            )
                        }
                        if (!device.hasPermission) {
                            Button(onClick = { actions.requestUsbPermission(device.deviceName) }) { Text(stringResource(R.string.request_permission)) }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    val report = buildDiagnosticReport(
                        state,
                        diagnosticMetadata(
                            context = context,
                            systemAutoRotationEnabled = systemAutoRotationEnabled,
                            controlRotationDegrees = controlRotationDegrees,
                        ),
                    )
                    copyToClipboard(context, "Open EOS Control diagnostic", report)
                    Toast.makeText(context, context.getString(R.string.diagnostic_copied), Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppSurfaceHigh,
                    contentColor = AppText,
                ),
                shape = RoundedCornerShape(6.dp),
            ) {
                Icon(painterResource(LucideR.drawable.lucide_ic_copy), null)
                Text(stringResource(R.string.copy_diagnostic), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

private fun diagnosticMetadata(
    context: Context,
    systemAutoRotationEnabled: Boolean,
    controlRotationDegrees: Float,
): DiagnosticReportMetadata {
    val productVersion = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"
    return DiagnosticReportMetadata(
        productVersion = productVersion,
        systemAutoRotationEnabled = systemAutoRotationEnabled,
        controlRotationDegrees = controlRotationDegrees,
    )
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

@Composable
private fun transportLabel(transport: CameraTransport): String = stringResource(
    when (transport) {
        CameraTransport.CCAPI_NETWORK -> R.string.transport_ccapi_network
        CameraTransport.USB_PTP -> R.string.transport_usb_ptp
        CameraTransport.DESKTOP_BRIDGE -> R.string.transport_desktop_bridge
    },
)

@Composable
private fun networkRoutingLabel(routing: CameraNetworkRouting): String = stringResource(
    when (routing) {
        CameraNetworkRouting.SYSTEM_DEFAULT -> R.string.route_system_default
        CameraNetworkRouting.WIFI_BOUND -> R.string.route_wifi_bound
    },
)

@Composable
private fun systemNetworkTransportLabel(transport: SystemNetworkTransport): String = stringResource(
    when (transport) {
        SystemNetworkTransport.NONE -> R.string.system_network_none
        SystemNetworkTransport.WIFI -> R.string.system_network_wifi
        SystemNetworkTransport.CELLULAR -> R.string.system_network_cellular
        SystemNetworkTransport.ETHERNET -> R.string.system_network_ethernet
        SystemNetworkTransport.VPN -> R.string.system_network_vpn
        SystemNetworkTransport.OTHER -> R.string.system_network_other
    },
)

@Composable
private fun yesNoLabel(value: Boolean): String = stringResource(if (value) R.string.yes else R.string.no)

@Composable
private fun usbStateLabel(state: UsbDiagnosticState): String = stringResource(
    when (state) {
        UsbDiagnosticState.READY -> R.string.usb_state_ready
        UsbDiagnosticState.PERMISSION_NEEDED -> R.string.usb_state_permission_needed
        UsbDiagnosticState.CANON_NON_PTP -> R.string.usb_state_canon_non_ptp
        UsbDiagnosticState.NON_CANON_PTP -> R.string.usb_state_non_canon_ptp
        UsbDiagnosticState.UNKNOWN_USB -> R.string.usb_state_unknown
    },
)

@Composable
private fun DebugSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = AppAccent, fontWeight = FontWeight.Bold)
        HorizontalDivider(color = AppBorder)
        content()
    }
}

@Composable
private fun DebugValue(label: String, value: String, mono: Boolean = false, warning: Boolean = false) {
    SelectionContainer {
        Column(Modifier.fillMaxWidth()) {
            Text(label, color = AppMutedText)
            Text(value, color = if (warning) AppWarning else AppText, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
        }
    }
}
