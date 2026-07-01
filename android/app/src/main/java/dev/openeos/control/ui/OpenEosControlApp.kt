package dev.openeos.control.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.text.selection.SelectionContainer
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraStatus
import dev.openeos.control.data.createUnsafeOkHttpClient
import okhttp3.OkHttpClient
import coil.ImageLoader

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
                    onDisconnect = viewModel::disconnect,
                    onRefresh = viewModel::refresh,
                    onRefreshLiveView = viewModel::refreshLiveViewFrame,
                    onLiveViewAutoRefreshChange = viewModel::setLiveViewAutoRefresh,
                    onToggleRecording = viewModel::toggleRecording,
                    onSetIso = viewModel::setIso,
                    onSetShutter = viewModel::setShutter,
                    onSetAperture = viewModel::setAperture,
                    onSetWhiteBalance = viewModel::setWhiteBalance,
                    onTapFocus = viewModel::tapFocus,
                    onClearError = viewModel::clearError,
                    onUseDirectCamera = viewModel::useDirectCameraPreset,
                    onUseDirectCameraHttps = viewModel::useDirectCameraHttpsPreset,
                    onUseDevSimulator = viewModel::useDevSimulatorPreset,
                ),
            )
        }
    }
}

private data class CameraActions(
    val onBaseUrlChange: (String) -> Unit,
    val onConnect: () -> Unit,
    val onDisconnect: () -> Unit,
    val onRefresh: () -> Unit,
    val onRefreshLiveView: () -> Unit,
    val onLiveViewAutoRefreshChange: (Boolean) -> Unit,
    val onToggleRecording: () -> Unit,
    val onSetIso: (String) -> Unit,
    val onSetShutter: (String) -> Unit,
    val onSetAperture: (String) -> Unit,
    val onSetWhiteBalance: (String) -> Unit,
    val onTapFocus: (Double, Double) -> Unit,
    val onClearError: () -> Unit,
    val onUseDirectCamera: () -> Unit,
    val onUseDirectCameraHttps: () -> Unit,
    val onUseDevSimulator: () -> Unit,
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CameraControlScreen(
    state: CameraUiState,
    actions: CameraActions,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        if (maxWidth < 720.dp) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConnectionColumn(
                    state = state,
                    actions = actions,
                    modifier = Modifier.fillMaxWidth(),
                )
                CameraControlsColumn(
                    state = state,
                    actions = actions,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            val leftScrollState = rememberScrollState()
            val rightScrollState = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ConnectionColumn(
                    state = state,
                    actions = actions,
                    modifier = Modifier
                        .weight(0.38f)
                        .fillMaxSize()
                        .verticalScroll(leftScrollState),
                )
                CameraControlsColumn(
                    state = state,
                    actions = actions,
                    modifier = Modifier
                        .weight(0.62f)
                        .fillMaxSize()
                        .verticalScroll(rightScrollState),
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ConnectionColumn(
    state: CameraUiState,
    actions: CameraActions,
    modifier: Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeaderBlock()
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = actions.onBaseUrlChange,
            label = { Text("Direct camera URL") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth(),
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
                Text("Direct (HTTP)")
            }
            SecondaryButton(enabled = !state.busy, onClick = actions.onUseDirectCameraHttps) {
                Text("Direct (HTTPS)")
            }
            SecondaryButton(enabled = !state.busy, onClick = actions.onUseDevSimulator) {
                Text("Dev Simulator")
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(enabled = !state.busy, onClick = actions.onConnect) {
                Text(if (state.busy) "Working" else "Connect")
            }
            SecondaryButton(
                enabled = state.connected && !state.busy,
                onClick = actions.onRefresh,
            ) {
                Text("Refresh")
            }
            SecondaryButton(
                enabled = state.connected && !state.busy,
                onClick = actions.onDisconnect,
            ) {
                Text("Disconnect")
            }
        }
        CameraSummary(state.info, state.status)
        state.error?.let {
            ErrorPanel(message = it, onClick = actions.onClearError)
        }
    }
}

@Composable
private fun CameraControlsColumn(
    state: CameraUiState,
    actions: CameraActions,
    modifier: Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MonitorPanel(
            status = state.status,
            liveViewFrameUrl = state.liveViewFrameUrl,
            liveViewBitmap = state.liveViewBitmap,
            liveViewAutoRefresh = state.liveViewAutoRefresh,
            focusPoint = state.focusPoint,
            enabled = state.connected && !state.busy,
            onTapFocus = actions.onTapFocus,
            onRefreshLiveView = actions.onRefreshLiveView,
            onLiveViewAutoRefreshChange = actions.onLiveViewAutoRefreshChange,
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

@Composable
private fun WifiIcon(connected: Boolean, modifier: Modifier = Modifier) {
    val color = if (connected) AppAccent else AppMutedText
    Canvas(modifier = modifier.size(24.dp)) {
        val width = size.width
        val height = size.height

        drawCircle(
            color = color,
            radius = width * 0.1f,
            center = Offset(width / 2f, height * 0.85f)
        )

        val strokeWidth = width * 0.08f

        drawArc(
            color = color,
            startAngle = 220f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(width * 0.3f, height * 0.5f),
            size = Size(width * 0.4f, height * 0.4f),
            style = Stroke(width = strokeWidth)
        )

        drawArc(
            color = color,
            startAngle = 220f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(width * 0.1f, height * 0.25f),
            size = Size(width * 0.8f, height * 0.8f),
            style = Stroke(width = strokeWidth)
        )
    }
}

@Composable
private fun BatteryIcon(level: Int, modifier: Modifier = Modifier) {
    val color = when {
        level > 50 -> Color(0xFF10B981)
        level > 20 -> Color(0xFFFBBF24)
        else -> Color(0xFFEF4444)
    }
    Canvas(modifier = modifier.size(height = 14.dp, width = 28.dp)) {
        val w = size.width
        val h = size.height

        val strokeWidth = 2.dp.toPx()
        val capWidth = 3.dp.toPx()

        drawRoundRect(
            color = AppMutedText,
            topLeft = Offset.Zero,
            size = Size(w - capWidth - 2.dp.toPx(), h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            style = Stroke(width = strokeWidth)
        )

        drawRoundRect(
            color = AppMutedText,
            topLeft = Offset(w - capWidth, h * 0.3f),
            size = Size(capWidth, h * 0.4f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx(), 1.dp.toPx())
        )

        val fillWidth = (w - capWidth - 6.dp.toPx()) * (level / 100f)
        if (fillWidth > 0) {
            drawRoundRect(
                color = color,
                topLeft = Offset(strokeWidth + 1.dp.toPx(), strokeWidth + 1.dp.toPx()),
                size = Size(fillWidth, h - (strokeWidth + 1.dp.toPx()) * 2f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx(), 1.dp.toPx())
            )
        }
    }
}

@Composable
private fun StorageIcon(available: Boolean, modifier: Modifier = Modifier) {
    val color = if (available) Color(0xFF10B981) else AppMutedText
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height

        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.2f, h * 0.1f)
            lineTo(w * 0.65f, h * 0.1f)
            lineTo(w * 0.8f, h * 0.25f)
            lineTo(w * 0.8f, h * 0.9f)
            lineTo(w * 0.2f, h * 0.9f)
            close()
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.dp.toPx())
        )

        val pinW = w * 0.06f
        val pinH = h * 0.15f
        for (i in 0..3) {
            drawRect(
                color = color,
                topLeft = Offset(w * (0.3f + i * 0.12f), h * 0.25f),
                size = Size(pinW, pinH)
            )
        }
    }
}

@Composable
private fun HeaderBlock() {
    Panel {
        Text("Open EOS Control", color = AppText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        Text("Direct Canon EOS CCAPI client interface", color = AppSubtleText, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CameraSummary(info: CameraInfo?, status: CameraStatus?) {
    Panel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            WifiIcon(connected = info != null)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info?.model ?: "Disconnected",
                    color = AppText,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = if (info != null) "API: ${info.api}" else "Connect to camera Wi-Fi hotspot",
                    color = AppSubtleText,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (info != null) {
            Spacer(modifier = Modifier.height(6.dp))
            androidx.compose.material3.HorizontalDivider(color = AppBorder.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BatteryIcon(level = status?.batteryLevel ?: 0)
                    val batteryText = when (status?.batteryStatus) {
                        "full" -> "Full"
                        "middle" -> "Medium"
                        "low" -> "Low"
                        "empty" -> "Empty"
                        else -> {
                            val level = status?.batteryLevel ?: 0
                            if (level > 0) "$level%" else "Unknown"
                        }
                    }
                    Text(
                        text = batteryText,
                        color = AppSubtleText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StorageIcon(available = status?.mediaAvailable == true)
                    Text(
                        text = if (status?.mediaAvailable == true) "SD Card OK" else "No Card",
                        color = if (status?.mediaAvailable == true) Color(0xFF10B981) else AppMutedText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.HorizontalDivider(color = AppBorder.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(6.dp))
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Raw Battery JSON:",
                        color = AppMutedText,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = status?.rawBatteryJson ?: "null",
                        color = AppSubtleText,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Raw Storage JSON:",
                        color = AppMutedText,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = status?.rawStorageJson ?: "null",
                        color = AppSubtleText,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun MonitorPanel(
    status: CameraStatus?,
    liveViewFrameUrl: String?,
    liveViewBitmap: Bitmap?,
    liveViewAutoRefresh: Boolean,
    focusPoint: FocusPoint?,
    enabled: Boolean,
    onTapFocus: (Double, Double) -> Unit,
    onRefreshLiveView: () -> Unit,
    onLiveViewAutoRefreshChange: (Boolean) -> Unit,
) {
    MonitorFrame(
        status = status,
        liveViewFrameUrl = liveViewFrameUrl,
        liveViewBitmap = liveViewBitmap,
        liveViewAutoRefresh = liveViewAutoRefresh,
        focusPoint = focusPoint,
        enabled = enabled,
        onTapFocus = onTapFocus,
        onRefreshLiveView = onRefreshLiveView,
        onLiveViewAutoRefreshChange = onLiveViewAutoRefreshChange,
    )
}

@Composable
private fun MonitorFrame(
    status: CameraStatus?,
    liveViewFrameUrl: String?,
    liveViewBitmap: Bitmap?,
    liveViewAutoRefresh: Boolean,
    focusPoint: FocusPoint?,
    enabled: Boolean,
    onTapFocus: (Double, Double) -> Unit,
    onRefreshLiveView: () -> Unit,
    onLiveViewAutoRefreshChange: (Boolean) -> Unit,
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
            if (liveViewBitmap != null) {
                Image(
                    bitmap = liveViewBitmap.asImageBitmap(),
                    contentDescription = "Live view frame",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                MonitorStatusOverlay(status = status, enabled = enabled)
                ViewfinderOverlay(recording = status?.recording == true)
            } else if (liveViewFrameUrl != null) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val imageLoader = remember(context, liveViewFrameUrl) {
                    ImageLoader.Builder(context)
                        .okHttpClient {
                            if (liveViewFrameUrl.startsWith("https://")) {
                                createUnsafeOkHttpClient()
                            } else {
                                OkHttpClient()
                            }
                        }
                        .build()
                }
                val imageRequest = remember(context, liveViewFrameUrl) {
                    val builder = ImageRequest.Builder(context)
                        .data(liveViewFrameUrl)
                        .crossfade(false)
                    if (liveViewFrameUrl.contains("ccapi/liveview/frame")) {
                        builder.decoderFactory(SvgDecoder.Factory())
                    }
                    builder.build()
                }
                AsyncImage(
                    model = imageRequest,
                    imageLoader = imageLoader,
                    contentDescription = "Live view frame",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                MonitorStatusOverlay(status = status, enabled = enabled)
                ViewfinderOverlay(recording = status?.recording == true)
            } else {
                MonitorPlaceholder(enabled = enabled)
            }
            FocusOverlay(focusPoint = focusPoint)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Live view", color = AppText, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(if (liveViewAutoRefresh) "Auto" else "Manual", color = AppSubtleText)
            Switch(
                checked = liveViewAutoRefresh,
                enabled = enabled,
                onCheckedChange = onLiveViewAutoRefreshChange,
            )
            SecondaryButton(enabled = enabled, onClick = onRefreshLiveView) {
                Text("Frame")
            }
        }
    }
}

@Composable
private fun MonitorPlaceholder(enabled: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("LIVE VIEW", color = AppSubtleText, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (enabled) "Tap monitor to focus" else "Connect camera to load frame",
            color = AppMutedText,
        )
    }
}

@Composable
private fun MonitorStatusOverlay(status: CameraStatus?, enabled: Boolean) {
    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color(0xAA05070A), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (status?.recording == true) "REC" else "STBY",
                color = if (status?.recording == true) Color(0xFFFF3B5B) else AppSubtleText,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "ISO ${status?.exposure?.iso ?: "-"}",
                color = AppSubtleText,
            )
            Text(status?.exposure?.shutter ?: "-", color = AppSubtleText)
            Text("F${status?.exposure?.aperture ?: "-"}", color = AppSubtleText)
        }
        Text(
            if (enabled) "Tap to focus" else "Connect camera",
            color = AppSubtleText,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .background(Color(0xAA05070A), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ViewfinderOverlay(recording: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val strokePx = 1.5f.dp.toPx()
        val bracketLen = 16.dp.toPx()
        val margin = 8.dp.toPx()
        val color = if (recording) AppDanger.copy(alpha = 0.8f) else AppText.copy(alpha = 0.4f)

        // Top Left
        drawLine(color, Offset(margin, margin), Offset(margin + bracketLen, margin), strokePx)
        drawLine(color, Offset(margin, margin), Offset(margin, margin + bracketLen), strokePx)

        // Top Right
        drawLine(color, Offset(w - margin, margin), Offset(w - margin - bracketLen, margin), strokePx)
        drawLine(color, Offset(w - margin, margin), Offset(w - margin, margin + bracketLen), strokePx)

        // Bottom Left
        drawLine(color, Offset(margin, h - margin), Offset(margin + bracketLen, h - margin), strokePx)
        drawLine(color, Offset(margin, h - margin), Offset(margin, h - margin - bracketLen), strokePx)

        // Bottom Right
        drawLine(color, Offset(w - margin, h - margin), Offset(w - margin - bracketLen, h - margin), strokePx)
        drawLine(color, Offset(w - margin, h - margin), Offset(w - margin, h - margin - bracketLen), strokePx)

        // Center reticle
        drawCircle(color, radius = 2.dp.toPx(), center = Offset(w / 2f, h / 2f))
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
        val boxSize = 48.dp.toPx()
        val color = Color(0xFF10B981)
        drawRect(
            color = color,
            topLeft = Offset(center.x - boxSize / 2f, center.y - boxSize / 2f),
            size = Size(boxSize, boxSize),
            style = Stroke(width = strokePx),
        )
        drawLine(
            color = color,
            start = Offset(center.x - boxSize * 0.4f, center.y),
            end = Offset(center.x + boxSize * 0.4f, center.y),
            strokeWidth = strokePx,
        )
        drawLine(
            color = color,
            start = Offset(center.x, center.y - boxSize * 0.4f),
            end = Offset(center.x, center.y + boxSize * 0.4f),
            strokeWidth = strokePx,
        )
    }
}

@Composable
private fun RecordButton(enabled: Boolean, recording: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Button(
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (recording) AppDanger else Color(0xFF1E293B),
            contentColor = Color.White,
            disabledContainerColor = AppPanelAlt,
            disabledContentColor = AppMutedText,
        ),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Canvas(modifier = Modifier.size(14.dp)) {
                drawCircle(
                    color = if (recording) Color.White else AppDanger,
                    radius = size.width / 2f
                )
            }
            Text(
                text = if (recording) "STOP RECORDING" else "START RECORDING",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge
            )
        }
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
    if (values.isNotEmpty()) {
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
}

@Composable
private fun ErrorPanel(message: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF7F1D1D), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SelectionContainer {
            Text(message, color = Color(0xFFFFE4E6))
        }
        SecondaryButton(
            onClick = onClick,
        ) {
            Text("Clear Error")
        }
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
        shape = RoundedCornerShape(8.dp),
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
        shape = RoundedCornerShape(8.dp),
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
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) AppAccent else AppPanelAlt,
            contentColor = if (selected) Color(0xFF111318) else AppText,
        ),
        onClick = onClick,
    ) {
        content()
    }
}
