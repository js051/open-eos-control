package dev.openeos.control.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.openeos.control.R
import com.composables.icons.lucide.R as LucideR
import dev.openeos.control.data.CameraFeature

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolIconButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = AppText,
) {
    TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.size(48.dp),
        ) {
            Icon(painterResource(icon), description, tint = if (enabled) tint else AppMutedText)
        }
    }
}

@Composable
fun ModeSegment(
    firstLabel: String,
    secondLabel: String,
    firstSelected: Boolean,
    onFirst: () -> Unit,
    onSecond: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .background(AppSurfaceHigh, RoundedCornerShape(6.dp))
            .padding(3.dp),
    ) {
        SegmentItem(firstLabel, firstSelected, onFirst, Modifier.weight(1f))
        SegmentItem(secondLabel, !firstSelected, onSecond, Modifier.weight(1f))
    }
}

@Composable
private fun SegmentItem(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (selected) AppBorder else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .semantics { role = Role.Tab },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) AppText else AppSubtleText, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun CameraHeader(state: CameraUiState, actions: CameraActions) {
    val battery = state.status?.batteryLevel?.let { stringResource(R.string.battery_percent, it) }
        ?: stringResource(R.string.unknown)
    val storage = when (state.status?.mediaAvailable) {
        true -> stringResource(R.string.storage_ready)
        else -> stringResource(R.string.storage_unknown)
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(AppSuccess, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(
                state.info?.model ?: stringResource(R.string.unknown),
                color = AppText,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("$battery | $storage", color = AppSubtleText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ModeSegment(
            firstLabel = stringResource(R.string.control),
            secondLabel = stringResource(R.string.debug),
            firstSelected = state.uiMode == UiMode.CONTROL,
            onFirst = { actions.setUiMode(UiMode.CONTROL) },
            onSecond = { actions.setUiMode(UiMode.DEBUG) },
            modifier = Modifier.width(132.dp),
        )
        ToolIconButton(LucideR.drawable.lucide_ic_unplug, stringResource(R.string.disconnect), actions.disconnect)
    }
}

@Composable
fun LiveViewFrame(state: CameraUiState, actions: CameraActions, modifier: Modifier = Modifier) {
    val bitmap = state.liveViewBitmap
    val sourceAspectRatio = bitmap?.takeIf { it.width > 0 && it.height > 0 }
        ?.let { it.width.toFloat() / it.height.toFloat() } ?: 16f / 9f
    val canFocus = state.supports(CameraFeature.TAP_FOCUS)
    val context = LocalContext.current
    var lastFramePainter by remember { mutableStateOf<Painter?>(null) }
    val imageLoader = remember(context) {
        ImageLoader.Builder(context).build()
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .pointerInput(canFocus, sourceAspectRatio) {
                detectTapGestures { offset ->
                    if (canFocus) {
                        mapLiveViewTap(
                            tapX = offset.x,
                            tapY = offset.y,
                            containerWidth = size.width.toFloat(),
                            containerHeight = size.height.toFloat(),
                            sourceAspectRatio = sourceAspectRatio,
                        )?.let { actions.tapFocus(it.x, it.y) }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap.asImageBitmap(),
                stringResource(R.string.live_view),
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            state.liveViewFrameUrl != null -> AsyncImage(
                model = ImageRequest.Builder(context).data(state.liveViewFrameUrl).crossfade(false).build(),
                imageLoader = imageLoader,
                placeholder = lastFramePainter,
                contentDescription = stringResource(R.string.live_view),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                onSuccess = { result -> lastFramePainter = result.painter },
            )
            else -> Text(stringResource(R.string.live_view_unavailable), color = AppMutedText)
        }

        Row(
            Modifier.align(Alignment.TopStart).padding(12.dp).background(Color(0xA6000000), RoundedCornerShape(4.dp)).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.status?.recording == true) Text(stringResource(R.string.recording), color = AppRecord, fontWeight = FontWeight.Bold)
            if (canFocus) Text(stringResource(R.string.tap_to_focus), color = AppAccent)
        }
        FocusIndicator(state.focusPoint, sourceAspectRatio)
        if (state.captureFeedback == CaptureFeedback.SUCCESS) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.72f)))
    }
}

@Composable
private fun FocusIndicator(point: FocusPoint?, sourceAspectRatio: Float) {
    if (point == null) return
    Canvas(Modifier.fillMaxSize()) {
        val display = mapFocusPointToDisplay(point, size.width, size.height, sourceAspectRatio)
        drawCircle(AppAccent, 28.dp.toPx(), Offset(display.x, display.y), style = Stroke(2.dp.toPx()))
        drawLine(AppAccent, Offset(display.x - 36.dp.toPx(), display.y), Offset(display.x - 18.dp.toPx(), display.y), 2.dp.toPx())
        drawLine(AppAccent, Offset(display.x + 18.dp.toPx(), display.y), Offset(display.x + 36.dp.toPx(), display.y), 2.dp.toPx())
    }
}

@Composable
fun ExposureStrip(state: CameraUiState, actions: CameraActions, modifier: Modifier = Modifier) {
    val exposure = state.status?.exposure
    Row(modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        ExposureCell(stringResource(R.string.iso), exposure?.iso ?: "-", state.capabilities?.iso?.isNotEmpty() == true) { actions.openPicker(SettingPicker.ISO) }
        ExposureCell(stringResource(R.string.shutter), exposure?.shutter ?: "-", state.capabilities?.shutter?.isNotEmpty() == true) { actions.openPicker(SettingPicker.SHUTTER) }
        ExposureCell(stringResource(R.string.aperture), exposure?.aperture ?: "-", state.capabilities?.aperture?.isNotEmpty() == true) { actions.openPicker(SettingPicker.APERTURE) }
        ExposureCell(stringResource(R.string.white_balance), exposure?.whiteBalance ?: "-", state.capabilities?.whiteBalance?.isNotEmpty() == true) { actions.openPicker(SettingPicker.WHITE_BALANCE) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ExposureCell(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxSize().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = AppMutedText, maxLines = 1)
        Text(value, color = if (enabled) AppText else AppMutedText, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CaptureButton(state: CameraUiState, actions: CameraActions) {
    val photo = state.captureMode == CaptureMode.PHOTO
    val supported = state.supports(if (photo) CameraFeature.STILL_CAPTURE else CameraFeature.VIDEO_RECORDING)
    val description = when {
        photo -> stringResource(R.string.capture_photo)
        state.status?.recording == true -> stringResource(R.string.stop_recording)
        else -> stringResource(R.string.start_recording)
    }
    val color = if (photo) AppText else AppRecord
    Box(
        Modifier.size(76.dp)
            .background(AppBackground, CircleShape)
            .clickable(enabled = supported && !state.busy) { if (photo) actions.captureStill() else actions.toggleRecording() }
            .semantics { contentDescription = description; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(if (photo) 58.dp else 52.dp).background(color, if (photo || state.status?.recording != true) CircleShape else RoundedCornerShape(8.dp)))
    }
}

@Composable
fun ErrorBanner(error: String?, onDismiss: () -> Unit) {
    if (error == null) return
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF512326)).padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(error, color = AppText, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        ToolIconButton(LucideR.drawable.lucide_ic_x, stringResource(R.string.dismiss), onDismiss)
    }
}
