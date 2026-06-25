package dev.openeos.control.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraStatus

@Composable
fun OpenEosControlApp(viewModel: CameraViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    MaterialTheme(colorScheme = OpenEosColorScheme) {
        Surface(color = AppBackground, modifier = Modifier.fillMaxSize()) {
            CameraControlScreen(
                state = state,
                actions = CameraActions(
                    onBaseUrlChange = viewModel::setBaseUrl,
                    onConnect = viewModel::connect,
                    onRefresh = viewModel::refresh,
                    onToggleRecording = viewModel::toggleRecording,
                    onSetIso = viewModel::setIso,
                    onSetShutter = viewModel::setShutter,
                    onSetAperture = viewModel::setAperture,
                    onSetWhiteBalance = viewModel::setWhiteBalance,
                    onTapFocus = viewModel::tapFocus,
                    onClearError = viewModel::clearError,
                    onUseDirectCamera = viewModel::useDirectCameraPreset,
                    onUseDevSimulator = viewModel::useDevSimulatorPreset,
                ),
            )
        }
    }
}

private data class CameraActions(
    val onBaseUrlChange: (String) -> Unit,
    val onConnect: () -> Unit,
    val onRefresh: () -> Unit,
    val onToggleRecording: () -> Unit,
    val onSetIso: (String) -> Unit,
    val onSetShutter: (String) -> Unit,
    val onSetAperture: (String) -> Unit,
    val onSetWhiteBalance: (String) -> Unit,
    val onTapFocus: (Double, Double) -> Unit,
    val onClearError: () -> Unit,
    val onUseDirectCamera: () -> Unit,
    val onUseDevSimulator: () -> Unit,
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CameraControlScreen(
    state: CameraUiState,
    actions: CameraActions,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(0.38f)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeaderBlock()
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = actions.onBaseUrlChange,
                label = { Text("Direct camera URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AppText,
                    unfocusedTextColor = AppText,
                    focusedLabelColor = AppAccent,
                    unfocusedLabelColor = AppMutedText,
                    cursorColor = AppAccent,
                    focusedBorderColor = AppAccent,
                    unfocusedBorderColor = AppBorder,
                    focusedContainerColor = Color(0xFF111827),
                    unfocusedContainerColor = Color(0xFF111827),
                ),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(enabled = !state.busy, onClick = actions.onUseDirectCamera) {
                    Text("Direct Camera")
                }
                SecondaryButton(enabled = !state.busy, onClick = actions.onUseDevSimulator) {
                    Text("Dev Simulator")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(enabled = !state.busy, onClick = actions.onConnect) {
                    Text(if (state.busy) "Working" else "Connect")
                }
                SecondaryButton(
                    enabled = state.connected && !state.busy,
                    onClick = actions.onRefresh,
                ) {
                    Text("Refresh")
                }
            }
            CameraSummary(state.info, state.status)
            state.error?.let {
                ErrorPanel(message = it, onClick = actions.onClearError)
            }
        }

        Column(
            modifier = Modifier
                .weight(0.62f)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MonitorPanel(
                status = state.status,
                focusPoint = state.focusPoint,
                enabled = state.connected && !state.busy,
                onTapFocus = actions.onTapFocus,
            )
            RecordButton(
                enabled = state.connected && !state.busy,
                recording = state.status?.recording == true,
                onClick = actions.onToggleRecording,
            )
            ControlSection("ISO", state.capabilities?.iso.orEmpty(), state.status?.exposure?.iso, actions.onSetIso)
            ControlSection(
                "Shutter",
                state.capabilities?.shutter.orEmpty(),
                state.status?.exposure?.shutter,
                actions.onSetShutter,
            )
            ControlSection(
                "Aperture",
                state.capabilities?.aperture.orEmpty(),
                state.status?.exposure?.aperture,
                actions.onSetAperture,
            )
            ControlSection(
                "White balance",
                state.capabilities?.whiteBalance.orEmpty(),
                state.status?.exposure?.whiteBalance,
                actions.onSetWhiteBalance,
            )
        }
    }
}

@Composable
private fun HeaderBlock() {
    Panel {
        Text("Open EOS Control", color = AppText, fontWeight = FontWeight.Bold)
        Text("Direct Canon EOS CCAPI control", color = AppSubtleText)
        Text("Simulator is for development only.", color = AppMutedText)
    }
}

@Composable
private fun CameraSummary(info: CameraInfo?, status: CameraStatus?) {
    Panel {
        Text(info?.model ?: "No camera connected", color = AppText, fontWeight = FontWeight.Bold)
        Text("API: ${info?.api ?: "-"}", color = AppSubtleText)
        Text("Battery: ${status?.batteryLevel ?: 0}% ${status?.batteryStatus ?: ""}", color = AppSubtleText)
        Text("Media: ${if (status?.mediaAvailable == true) "card ok" else "unknown"}", color = AppSubtleText)
        if (info == null) {
            Text("Connect phone to the camera Wi-Fi, then enter the camera CCAPI URL.", color = AppMutedText)
        }
    }
}

@Composable
private fun MonitorPanel(
    status: CameraStatus?,
    focusPoint: FocusPoint?,
    enabled: Boolean,
    onTapFocus: (Double, Double) -> Unit,
) {
    MonitorFrame(
        status = status,
        focusPoint = focusPoint,
        enabled = enabled,
        onTapFocus = onTapFocus,
    )
}

@Composable
private fun MonitorFrame(
    status: CameraStatus?,
    focusPoint: FocusPoint?,
    enabled: Boolean,
    onTapFocus: (Double, Double) -> Unit,
) {
    Panel {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(AppMonitor, RoundedCornerShape(8.dp))
                .border(1.dp, AppBorder, RoundedCornerShape(8.dp))
                .pointerInput(enabled) {
                    detectTapGestures { offset ->
                        if (enabled && size.width > 0 && size.height > 0) {
                            val x = (offset.x / size.width).coerceIn(0f, 1f).toDouble()
                            val y = (offset.y / size.height).coerceIn(0f, 1f).toDouble()
                            onTapFocus(x, y)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (status?.recording == true) "REC" else "STBY",
                    color = if (status?.recording == true) Color(0xFFFF3B5B) else AppSubtleText,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "ISO ${status?.exposure?.iso ?: "-"}  ${status?.exposure?.shutter ?: "-"}  F${status?.exposure?.aperture ?: "-"}",
                    color = AppSubtleText,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (enabled) "Tap monitor to focus" else "Connect camera to focus",
                    color = AppMutedText,
                )
            }
            FocusOverlay(focusPoint = focusPoint)
        }
    }
}

@Composable
private fun FocusOverlay(focusPoint: FocusPoint?) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (focusPoint == null) return@Canvas
        val strokePx = 2.dp.toPx()
        val center = Offset(
            x = (focusPoint.x.toFloat() * size.width).coerceIn(0f, size.width),
            y = (focusPoint.y.toFloat() * size.height).coerceIn(0f, size.height),
        )
        val boxSize = 52.dp.toPx()
        drawRect(
            color = AppAccent,
            topLeft = Offset(center.x - boxSize / 2f, center.y - boxSize / 2f),
            size = Size(boxSize, boxSize),
            style = Stroke(width = strokePx),
        )
        drawLine(
            color = AppAccent,
            start = Offset(center.x - boxSize * 0.8f, center.y),
            end = Offset(center.x + boxSize * 0.8f, center.y),
            strokeWidth = strokePx,
        )
        drawLine(
            color = AppAccent,
            start = Offset(center.x, center.y - boxSize * 0.8f),
            end = Offset(center.x, center.y + boxSize * 0.8f),
            strokeWidth = strokePx,
        )
    }
}

@Composable
private fun RecordButton(enabled: Boolean, recording: Boolean, onClick: () -> Unit) {
    Button(
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (recording) AppDanger else AppAccent,
            contentColor = if (recording) Color.White else Color(0xFF111318),
            disabledContainerColor = AppPanelAlt,
            disabledContentColor = AppMutedText,
        ),
        onClick = onClick,
    ) {
        Text(if (recording) "Stop REC" else "Start REC")
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ControlSection(
    label: String,
    values: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    Panel {
        Text(label, color = AppText, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEach { value ->
                ChoiceButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                ) {
                    Text(value)
                }
            }
        }
    }
}

@Composable
private fun ErrorPanel(message: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(Color(0xFF7F1D1D), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(message, color = Color(0xFFFFE4E6))
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppPanel, RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF263244), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun PrimaryButton(
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Button(
        enabled = enabled,
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = AppAccent,
            contentColor = Color(0xFF111318),
            disabledContainerColor = AppPanelAlt,
            disabledContentColor = AppMutedText,
        ),
    ) {
        content()
    }
}

@Composable
private fun SecondaryButton(
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Button(
        enabled = enabled,
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = AppPanelAlt,
            contentColor = AppText,
            disabledContainerColor = Color(0xFF1B2230),
            disabledContentColor = AppMutedText,
        ),
    ) {
        content()
    }
}

@Composable
private fun ChoiceButton(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Button(
        modifier = Modifier.widthIn(min = 72.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) AppAccent else AppPanelAlt,
            contentColor = if (selected) Color(0xFF111318) else AppText,
        ),
        onClick = onClick,
    ) {
        content()
    }
}
