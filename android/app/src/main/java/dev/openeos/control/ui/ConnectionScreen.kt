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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.openeos.control.R
import com.composables.icons.lucide.R as LucideR

@Composable
fun ConnectionScreen(state: CameraUiState, actions: CameraActions) {
    var showAuthentication by remember { mutableStateOf(state.username.isNotBlank()) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
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

            ModeSegment(
                firstLabel = stringResource(R.string.preset_http),
                secondLabel = stringResource(R.string.preset_https),
                firstSelected = state.baseUrl.startsWith("http://") && !state.baseUrl.contains("10.0.2.2"),
                onFirst = actions.useHttpPreset,
                onSecond = actions.useHttpsPreset,
            )
            Button(
                onClick = actions.useSimulatorPreset,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppSurfaceHigh,
                    contentColor = AppText,
                ),
                shape = RoundedCornerShape(6.dp),
            ) {
                Icon(painterResource(LucideR.drawable.lucide_ic_monitor_play), null, Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.preset_simulator))
            }
            Button(
                onClick = actions.enterOfflinePreview,
                modifier = Modifier.fillMaxWidth().height(48.dp),
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
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = actions.setBaseUrl,
                label = { Text(stringResource(R.string.camera_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier.fillMaxWidth().height(48.dp).clickable { showAuthentication = !showAuthentication },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.authentication), color = AppText, modifier = Modifier.weight(1f))
                Icon(painterResource(LucideR.drawable.lucide_ic_chevron_down), null, tint = AppSubtleText)
            }
            AnimatedVisibility(showAuthentication) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(state.username, actions.setUsername, label = { Text(stringResource(R.string.username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        state.password,
                        actions.setPassword,
                        label = { Text(stringResource(R.string.password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Button(
                onClick = actions.connect,
                enabled = !state.isBusy(CameraOperation.CONNECT) && state.baseUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(6.dp),
            ) {
                Icon(painterResource(LucideR.drawable.lucide_ic_wifi), null, Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(if (state.isBusy(CameraOperation.CONNECT)) R.string.connecting else R.string.connect))
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.usb_camera), color = AppText, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                ToolIconButton(LucideR.drawable.lucide_ic_refresh_cw, stringResource(R.string.usb_scan), actions.refreshUsb, enabled = !state.isBusy(CameraOperation.USB))
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
            state.usbDiagnostics.devices.forEach { device ->
                Column(Modifier.fillMaxWidth().background(AppSurface, RoundedCornerShape(6.dp)).padding(12.dp)) {
                    Text(device.displayName, color = AppText, fontWeight = FontWeight.SemiBold)
                    Text("VID %04X / PID %04X".format(device.vendorId, device.productId), color = AppSubtleText)
                    if (!device.hasPermission) {
                        Button(onClick = { actions.requestUsbPermission(device.deviceName) }, modifier = Modifier.padding(top = 8.dp)) {
                            Text(stringResource(R.string.request_permission))
                        }
                    } else if (device.isCanon && device.hasPtpInterface) {
                        Button(
                            onClick = {
                                actions.connectUsb(device.deviceName, device.vendorId, device.productId)
                            },
                            enabled = !state.isBusy(CameraOperation.CONNECT),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Icon(painterResource(LucideR.drawable.lucide_ic_usb), null, Modifier.size(20.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(
                                stringResource(
                                    if (state.isBusy(CameraOperation.CONNECT)) {
                                        R.string.connecting
                                    } else {
                                        R.string.connect_usb_camera
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
