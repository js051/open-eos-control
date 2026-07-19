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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.openeos.control.R
import com.composables.icons.lucide.R as LucideR
import dev.openeos.control.data.CameraNetworkRouting
import dev.openeos.control.data.CameraTransport
import dev.openeos.control.data.UsbDiagnosticState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DebugScreen(state: CameraUiState, actions: CameraActions) {
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
                DebugValue(stringResource(R.string.transport), state.transport?.let { transportLabel(it) } ?: unknown)
                DebugValue(stringResource(R.string.api_version), state.info?.api ?: unknown)
                DebugValue(stringResource(R.string.last_error), state.error ?: none, warning = state.error != null)
            }
            DebugSection(stringResource(R.string.ccapi)) {
                DebugValue(stringResource(R.string.supported_features), state.capabilities?.matrix?.supported.orEmpty().joinToString { it.name }.ifBlank { none })
                DebugValue(stringResource(R.string.planned_features), state.capabilities?.matrix?.planned.orEmpty().joinToString { it.name }.ifBlank { none })
                DebugValue(stringResource(R.string.battery_raw), state.status?.rawBatteryJson?.ifBlank { unavailable } ?: unavailable, mono = true)
                DebugValue(stringResource(R.string.storage_raw), state.status?.rawStorageJson?.ifBlank { unavailable } ?: unavailable, mono = true)
            }
            DebugSection(stringResource(R.string.network)) {
                val network = state.networkDiagnostics
                DebugValue(stringResource(R.string.camera_route), networkRoutingLabel(network.routing))
                DebugValue(stringResource(R.string.target_host), network.targetHost ?: unknown, mono = true)
                DebugValue(stringResource(R.string.network_interface), network.interfaceName ?: unavailable, mono = true)
                DebugValue(stringResource(R.string.network_handle), network.networkHandle?.toString() ?: unavailable, mono = true)
                DebugValue(stringResource(R.string.wifi_available), yesNoLabel(network.wifiAvailable))
                DebugValue(stringResource(R.string.cellular_available), yesNoLabel(network.cellularAvailable))
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
            }
            DebugSection(stringResource(R.string.usb_ptp)) {
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
                    val report = buildDiagnosticReport(state)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Open EOS Control diagnostic", report))
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
