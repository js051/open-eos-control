package dev.openeos.control.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.openeos.control.R
import dev.openeos.control.data.ConnectionFailureReason
import com.composables.icons.lucide.R as LucideR

@Composable
fun ConnectionScreen(state: CameraUiState, actions: CameraActions) {
    var showAuthentication by remember { mutableStateOf(state.username.isNotBlank()) }
    LaunchedEffect(state.connectionRecovery) {
        if (state.connectionRecovery?.target == ConnectionAttemptTarget.CCAPI &&
            state.connectionRecovery.reason == ConnectionFailureReason.AUTHENTICATION_REJECTED
        ) {
            showAuthentication = true
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        if (!state.connected && (CameraOperation.CONNECT in state.pendingOperations || CameraOperation.BRIDGE in state.pendingOperations)) {
            Button(
                onClick = actions.cancelConnectionAttempt,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                    .heightIn(min = 48.dp).testTag("connection-cancel"),
                colors = ButtonDefaults.buttonColors(containerColor = AppSurfaceHigh, contentColor = AppText),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(stringResource(
                    if (CameraOperation.CONNECT in state.pendingOperations) R.string.cancel_connection else R.string.cancel_bridge_scan,
                ))
            }
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp)
                .testTag("connection-form"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_camera), null, tint = AppAccent, modifier = Modifier.size(44.dp))
                    ToolIconButton(
                        LucideR.drawable.lucide_ic_languages,
                        stringResource(R.string.language),
                        { actions.openPicker(SettingPicker.LANGUAGE) },
                    )
                }
                Text(stringResource(R.string.connect_title), color = AppText, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.connect_subtitle), color = AppSubtleText)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = actions.openDownloadHistory,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("download-history-open"),
                    ) {
                        Text(stringResource(R.string.download_history_title))
                    }
                    TextButton(
                        onClick = actions.openSavedJpegs,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("saved-jpegs-open"),
                    ) {
                        Text(stringResource(R.string.saved_jpegs_title))
                    }
                }

                ConnectionChoiceSegment(
                    firstLabel = stringResource(R.string.direct_camera),
                    secondLabel = stringResource(R.string.desktop_bridge),
                    firstSelected = state.connectionTarget == ConnectionTarget.CCAPI,
                    onFirst = { actions.setConnectionTarget(ConnectionTarget.CCAPI) },
                    onSecond = { actions.setConnectionTarget(ConnectionTarget.DESKTOP_BRIDGE) },
                    tag = "connection-target",
                )

                when (state.connectionTarget) {
                    ConnectionTarget.CCAPI -> CcapiConnectionControls(state, actions, showAuthentication) {
                        showAuthentication = !showAuthentication
                    }

                    ConnectionTarget.DESKTOP_BRIDGE -> DesktopBridgeConnectionControls(state, actions)
                }

                Button(
                    onClick = actions.enterOfflinePreview,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("connection-offline-preview"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppSurfaceHigh,
                        contentColor = AppText,
                    ),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_eye), null, Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.preview_interface))
                }

                Spacer(Modifier.height(12.dp))
                UsbConnectionControls(state, actions)
            }
        }
    }
}

@Composable
private fun CcapiConnectionControls(
    state: CameraUiState,
    actions: CameraActions,
    showAuthentication: Boolean,
    toggleAuthentication: () -> Unit,
) {
    ConnectionChoiceSegment(
        firstLabel = stringResource(R.string.preset_http),
        secondLabel = stringResource(R.string.preset_https),
        firstSelected = state.baseUrl.startsWith("http://") && !state.baseUrl.contains("10.0.2.2"),
        onFirst = actions.useHttpPreset,
        onSecond = actions.useHttpsPreset,
        tag = "connection-protocol",
    )
    Button(
        onClick = actions.useSimulatorPreset,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AppSurfaceHigh, contentColor = AppText),
        shape = RoundedCornerShape(6.dp),
    ) {
        Icon(painterResource(LucideR.drawable.lucide_ic_monitor_play), null, Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.preset_simulator))
    }
    OutlinedTextField(
        value = state.baseUrl,
        onValueChange = actions.setBaseUrl,
        label = { Text(stringResource(R.string.camera_url)) },
        supportingText = if (state.ccapiSimulatorMode == true) null else {
            { Text(stringResource(R.string.ccapi_setup_hint), color = AppSubtleText) }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("connection-camera-url"),
    )
    state.connectionRecovery?.takeIf { it.target == ConnectionAttemptTarget.CCAPI }?.let {
        ConnectionRecoveryCard(it, actions.clearError)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = toggleAuthentication)
            .testTag("connection-authentication-toggle"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.authentication), color = AppText, modifier = Modifier.weight(1f))
        Icon(painterResource(LucideR.drawable.lucide_ic_chevron_down), null, tint = AppSubtleText)
    }
    AnimatedVisibility(showAuthentication) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                state.username,
                actions.setUsername,
                label = { Text(stringResource(R.string.username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("connection-username"),
            )
            OutlinedTextField(
                state.password,
                actions.setPassword,
                label = { Text(stringResource(R.string.password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().testTag("connection-password"),
            )
        }
    }
    Button(
        onClick = actions.connect,
        enabled = !state.isBusy(CameraOperation.CONNECT) && !state.isBusy(CameraOperation.BRIDGE) && state.baseUrl.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("connection-connect"),
        shape = RoundedCornerShape(6.dp),
    ) {
        Icon(painterResource(LucideR.drawable.lucide_ic_wifi), null, Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(if (state.isBusy(CameraOperation.CONNECT)) R.string.connecting else R.string.connect))
    }
}

@Composable
private fun DesktopBridgeConnectionControls(state: CameraUiState, actions: CameraActions) {
    Text(stringResource(R.string.desktop_bridge_hint), color = AppSubtleText)
    OutlinedTextField(
        value = state.bridgeBaseUrl,
        onValueChange = actions.setBridgeBaseUrl,
        label = { Text(stringResource(R.string.desktop_bridge_url)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("connection-bridge-url"),
    )
    OutlinedTextField(
        value = state.bridgeToken,
        onValueChange = actions.setBridgeToken,
        label = { Text(stringResource(R.string.desktop_bridge_token)) },
        supportingText = { Text(stringResource(R.string.desktop_bridge_token_hint)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag("connection-bridge-token"),
    )
    state.connectionRecovery?.takeIf { it.target == ConnectionAttemptTarget.DESKTOP_BRIDGE }?.let {
        ConnectionRecoveryCard(it, actions.clearError)
    }
    Button(
        onClick = actions.scanDesktopBridge,
        enabled = !state.isBusy(CameraOperation.CONNECT) && !state.isBusy(CameraOperation.BRIDGE) && state.bridgeBaseUrl.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("connection-bridge-scan"),
        colors = ButtonDefaults.buttonColors(containerColor = AppSurfaceHigh, contentColor = AppText),
        shape = RoundedCornerShape(6.dp),
    ) {
        Icon(painterResource(LucideR.drawable.lucide_ic_refresh_cw), null, Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(
                if (state.isBusy(CameraOperation.BRIDGE)) R.string.scanning_desktop_bridge else R.string.scan_desktop_bridge
            )
        )
    }
    if (state.bridgeScanCompleted && state.bridgeCameras.isEmpty() && !state.isBusy(CameraOperation.BRIDGE)) {
        Text(
            stringResource(R.string.connection_bridge_scan_empty),
            color = AppSubtleText,
            modifier = Modifier.testTag("connection-bridge-scan-empty"),
        )
    }
    state.bridgeCameras.forEach { camera ->
        val selected = camera.id == state.selectedBridgeCameraId
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(if (selected) AppSurfaceHigh else AppSurface, RoundedCornerShape(6.dp))
                .clickable { actions.selectBridgeCamera(camera.id) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = { actions.selectBridgeCamera(camera.id) })
            Column(Modifier.weight(1f)) {
                Text(camera.model, color = AppText, fontWeight = FontWeight.SemiBold)
                Text("${camera.engine} · ${camera.port}", color = AppSubtleText)
            }
        }
    }
    Button(
        onClick = actions.connectBridge,
        enabled = !state.isBusy(CameraOperation.CONNECT) && !state.isBusy(CameraOperation.BRIDGE) && state.bridgeBaseUrl.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("connection-bridge-connect"),
        shape = RoundedCornerShape(6.dp),
    ) {
        Icon(painterResource(LucideR.drawable.lucide_ic_monitor_play), null, Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(
                if (state.isBusy(CameraOperation.CONNECT)) R.string.connecting else R.string.connect_desktop_bridge
            )
        )
    }
}

@Composable
private fun UsbConnectionControls(state: CameraUiState, actions: CameraActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.usb_camera),
            color = AppText,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        ToolIconButton(
            LucideR.drawable.lucide_ic_refresh_cw,
            stringResource(R.string.usb_scan),
            actions.refreshUsb,
            enabled = !state.isBusy(CameraOperation.USB),
            testTag = "connection-usb-scan",
        )
    }
    Text(
        pluralStringResource(
            R.plurals.usb_devices_found,
            state.usbDiagnostics.devices.size,
            state.usbDiagnostics.devices.size,
            state.usbDiagnostics.canonDeviceCount,
        ),
        color = AppSubtleText,
    )
    state.connectionRecovery?.takeIf { it.target == ConnectionAttemptTarget.USB }?.let {
        ConnectionRecoveryCard(it, actions.clearError)
    }
    if (state.usbDiagnostics.devices.isEmpty()) {
        Text(
            stringResource(R.string.connection_usb_empty),
            color = AppSubtleText,
            modifier = Modifier.testTag("connection-usb-empty"),
        )
    }
    state.usbDiagnostics.devices.forEach { device ->
        Column(Modifier.fillMaxWidth().background(AppSurface, RoundedCornerShape(6.dp)).padding(12.dp)) {
            Text(device.displayName, color = AppText, fontWeight = FontWeight.SemiBold)
            Text("VID %04X / PID %04X".format(device.vendorId, device.productId), color = AppSubtleText)
            val reason = when {
                !device.isCanon -> R.string.connection_usb_non_canon
                !device.hasPtpInterface -> R.string.connection_usb_no_ptp
                !device.hasPermission -> R.string.connection_usb_permission
                else -> null
            }
            reason?.let {
                Text(stringResource(it), color = AppSubtleText, modifier = Modifier.padding(top = 8.dp))
            }
            if (device.isCanon && device.hasPtpInterface && !device.hasPermission) {
                Button(
                    onClick = { actions.requestUsbPermission(device.deviceName) },
                    enabled = !state.isBusy(CameraOperation.USB),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)
                        .testTag("connection-usb-permission"),
                ) {
                    Text(stringResource(R.string.request_permission))
                }
            } else if (device.isCanon && device.hasPtpInterface && device.hasPermission) {
                Button(
                    onClick = { actions.connectUsb(device.deviceName, device.vendorId, device.productId) },
                    enabled = !state.isBusy(CameraOperation.CONNECT) && !state.isBusy(CameraOperation.BRIDGE),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)
                        .testTag("connection-usb-connect"),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_usb), null, Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(
                            if (state.isBusy(CameraOperation.CONNECT)) R.string.connecting else R.string.connect_usb_camera
                        )
                    )
                }
            }
        }
    }
}
