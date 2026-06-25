package dev.openeos.control.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

    MaterialTheme {
        Surface(color = Color(0xFF10131A), modifier = Modifier.fillMaxSize()) {
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
private fun CameraControlScreen(
    state: CameraUiState,
    actions: CameraActions,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(0.38f)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Open EOS Control", color = Color.White, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = actions.onBaseUrlChange,
                label = { Text("Direct camera URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !state.busy, onClick = actions.onUseDirectCamera) {
                    Text("Direct Camera")
                }
                Button(enabled = !state.busy, onClick = actions.onUseDevSimulator) {
                    Text("Dev Simulator")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !state.busy, onClick = actions.onConnect) {
                    Text(if (state.busy) "Working" else "Connect")
                }
                Button(
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
private fun CameraSummary(info: CameraInfo?, status: CameraStatus?) {
    Panel {
        Text(info?.model ?: "No camera connected", color = Color.White, fontWeight = FontWeight.Bold)
        Text("API: ${info?.api ?: "-"}", color = Color(0xFFCBD5E1))
        Text("Battery: ${status?.batteryLevel ?: 0}% ${status?.batteryStatus ?: ""}", color = Color(0xFFCBD5E1))
        Text("Media: ${if (status?.mediaAvailable == true) "card ok" else "unknown"}", color = Color(0xFFCBD5E1))
        if (info == null) {
            Text("Connect phone to the camera Wi-Fi, then enter the camera CCAPI URL.", color = Color(0xFF94A3B8))
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
                .height(190.dp)
                .background(Color(0xFF05070A), RoundedCornerShape(8.dp))
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
                    color = if (status?.recording == true) Color(0xFFFF3B5B) else Color(0xFFE2E8F0),
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "ISO ${status?.exposure?.iso ?: "-"}  ${status?.exposure?.shutter ?: "-"}  F${status?.exposure?.aperture ?: "-"}",
                    color = Color(0xFFE2E8F0),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (enabled) "Tap monitor to focus" else "Connect camera to focus",
                    color = Color(0xFF94A3B8),
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
            color = Color(0xFFFACC15),
            topLeft = Offset(center.x - boxSize / 2f, center.y - boxSize / 2f),
            size = Size(boxSize, boxSize),
            style = Stroke(width = strokePx),
        )
        drawLine(
            color = Color(0xFFFACC15),
            start = Offset(center.x - boxSize * 0.8f, center.y),
            end = Offset(center.x + boxSize * 0.8f, center.y),
            strokeWidth = strokePx,
        )
        drawLine(
            color = Color(0xFFFACC15),
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
            containerColor = if (recording) Color(0xFFBE123C) else Color(0xFFFACC15),
            contentColor = if (recording) Color.White else Color(0xFF111318),
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
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEach { value ->
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (value == selected) Color(0xFFFACC15) else Color(0xFF263041),
                        contentColor = if (value == selected) Color(0xFF111318) else Color.White,
                    ),
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
            .background(Color(0xFF1A202C), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}
