package dev.openeos.control.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.openeos.control.data.CameraCapabilities
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraStatus
import dev.openeos.control.data.CcapiClient
import kotlinx.coroutines.launch

@Composable
fun OpenEosControlApp() {
    MaterialTheme {
        Surface(color = Color(0xFF10131A), modifier = Modifier.fillMaxSize()) {
            CameraControlScreen()
        }
    }
}

@Composable
private fun CameraControlScreen() {
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf("http://10.0.2.2:18080") }
    var info by remember { mutableStateOf<CameraInfo?>(null) }
    var status by remember { mutableStateOf<CameraStatus?>(null) }
    var capabilities by remember { mutableStateOf<CameraCapabilities?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun runCamera(block: suspend (CcapiClient) -> Unit) {
        scope.launch {
            busy = true
            error = null
            try {
                block(CcapiClient(baseUrl))
            } catch (exception: Exception) {
                error = exception.message ?: "Camera request failed"
            } finally {
                busy = false
            }
        }
    }

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
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Camera URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    onClick = {
                        runCamera { client ->
                            info = client.info()
                            status = client.status()
                            capabilities = client.capabilities()
                        }
                    },
                ) {
                    Text(if (busy) "Working" else "Connect")
                }
                Button(
                    enabled = status != null && !busy,
                    onClick = { runCamera { status = it.status() } },
                ) {
                    Text("Refresh")
                }
            }
            CameraSummary(info, status)
            error?.let { ErrorPanel(it) }
        }

        Column(
            modifier = Modifier
                .weight(0.62f)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MonitorPanel(status)
            RecordButton(
                enabled = status != null && !busy,
                recording = status?.recording == true,
                onClick = {
                    runCamera { client ->
                        status = if (status?.recording == true) {
                            client.stopRecording()
                        } else {
                            client.startRecording()
                        }
                    }
                },
            )
            ControlSection("ISO", capabilities?.iso.orEmpty(), status?.exposure?.iso) { value ->
                runCamera { status = it.setExposure(iso = value) }
            }
            ControlSection("Shutter", capabilities?.shutter.orEmpty(), status?.exposure?.shutter) { value ->
                runCamera { status = it.setExposure(shutter = value) }
            }
            ControlSection("Aperture", capabilities?.aperture.orEmpty(), status?.exposure?.aperture) { value ->
                runCamera { status = it.setExposure(aperture = value) }
            }
            ControlSection(
                "White balance",
                capabilities?.whiteBalance.orEmpty(),
                status?.exposure?.whiteBalance,
            ) { value ->
                runCamera { status = it.setWhiteBalance(value) }
            }
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
    }
}

@Composable
private fun MonitorPanel(status: CameraStatus?) {
    Panel {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .background(Color(0xFF05070A), RoundedCornerShape(8.dp)),
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
            }
        }
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
private fun ErrorPanel(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
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
