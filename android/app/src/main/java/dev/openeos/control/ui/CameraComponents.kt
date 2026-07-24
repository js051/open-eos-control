package dev.openeos.control.ui

import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.openeos.control.R
import com.composables.icons.lucide.R as LucideR
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.NativeLiveViewSession
import kotlinx.coroutines.delay

@Composable
fun Modifier.cameraControlRotation(): Modifier {
    val rotation = LocalCameraControlRotation.current
    return graphicsLayer { rotationZ = rotation }
}

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
            Icon(
                painterResource(icon),
                description,
                Modifier.cameraControlRotation(),
                tint = if (enabled) tint else AppMutedText,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveViewFpsButton(
    state: CameraUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.fps_control_description, state.liveViewFrameRateFps)
    TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(stringResource(R.string.live_view_frame_rate)) } },
        state = rememberTooltipState(),
    ) {
        Column(
            modifier
                .size(64.dp)
                .testTag("fps-control")
                .clickable(onClick = onClick)
                .semantics { contentDescription = description; role = Role.Button },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painterResource(LucideR.drawable.lucide_ic_gauge),
                null,
                Modifier.size(24.dp).cameraControlRotation(),
                tint = AppAccent,
            )
            Text(
                stringResource(R.string.fps_compact, state.liveViewFrameRateFps),
                Modifier.cameraControlRotation(),
                color = AppText,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
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
fun CameraOverlayHeader(state: CameraUiState, actions: CameraActions, modifier: Modifier = Modifier) {
    val battery = state.status?.batteryLevel?.let { stringResource(R.string.battery_percent, it) }
        ?: stringResource(R.string.unknown)
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth().height(48.dp).background(Color(0xB8000000)).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).background(AppSuccess, CircleShape))
        Text(
            state.info?.model ?: stringResource(R.string.unknown),
            color = AppText,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).cameraControlRotation(),
        )
        Text(battery, Modifier.cameraControlRotation(), color = AppSubtleText, maxLines = 1)
        ToolIconButton(
            if (state.captureMode == CaptureMode.PHOTO) LucideR.drawable.lucide_ic_camera else LucideR.drawable.lucide_ic_video,
            stringResource(if (state.captureMode == CaptureMode.PHOTO) R.string.switch_to_video else R.string.switch_to_photo),
            {
                actions.setCaptureMode(
                    if (state.captureMode == CaptureMode.PHOTO) CaptureMode.VIDEO else CaptureMode.PHOTO,
                )
            },
            enabled = !state.isBusy(CameraOperation.SETTING),
            tint = if (state.captureMode == CaptureMode.VIDEO) AppRecord else AppText,
        )
        ToolIconButton(
            LucideR.drawable.lucide_ic_eye_off,
            stringResource(R.string.hide_hud),
            { actions.setHudVisible(false) },
        )
        Box {
            ToolIconButton(
                LucideR.drawable.lucide_ic_ellipsis_vertical,
                stringResource(R.string.more_actions),
                { menuExpanded = true },
            )
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                if (state.supports(CameraFeature.MEDIA_BROWSER)) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.camera_media)) },
                        leadingIcon = { Icon(painterResource(LucideR.drawable.lucide_ic_images), null) },
                        onClick = {
                            menuExpanded = false
                            actions.setUiMode(UiMode.MEDIA)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.language)) },
                    leadingIcon = { Icon(painterResource(LucideR.drawable.lucide_ic_languages), null) },
                    onClick = {
                        menuExpanded = false
                        actions.openPicker(SettingPicker.LANGUAGE)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.debug)) },
                    leadingIcon = { Icon(painterResource(LucideR.drawable.lucide_ic_bug), null) },
                    onClick = {
                        menuExpanded = false
                        actions.setUiMode(UiMode.DEBUG)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.disconnect)) },
                    leadingIcon = { Icon(painterResource(LucideR.drawable.lucide_ic_unplug), null) },
                    onClick = {
                        menuExpanded = false
                        actions.disconnect()
                    },
                )
            }
        }
    }
}

@Composable
fun LiveViewFrame(state: CameraUiState, actions: CameraActions, modifier: Modifier = Modifier) {
    val bitmap = state.liveViewBitmap
    val sourceAspectRatio = bitmap?.takeIf { it.width > 0 && it.height > 0 }
        ?.let { it.width.toFloat() / it.height.toFloat() } ?: state.liveViewAspectRatio
    val tapAction = when {
        state.liveViewTapAction == LiveViewTapAction.WHITE_BALANCE &&
            state.supports(CameraFeature.CLICK_WHITE_BALANCE) -> LiveViewTapAction.WHITE_BALANCE
        state.supports(CameraFeature.TAP_FOCUS) -> LiveViewTapAction.FOCUS
        state.supports(CameraFeature.CLICK_WHITE_BALANCE) -> LiveViewTapAction.WHITE_BALANCE
        else -> null
    }
    val canTap = when (tapAction) {
        LiveViewTapAction.FOCUS -> !state.isBusy(CameraOperation.FOCUS)
        LiveViewTapAction.WHITE_BALANCE -> !state.isBusy(CameraOperation.SETTING)
        null -> false
    }
    val context = LocalContext.current
    var lastFramePainter by remember { mutableStateOf<Painter?>(null) }
    val imageLoader = remember(context) {
        ImageLoader.Builder(context).build()
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .pointerInput(canTap, tapAction, sourceAspectRatio) {
                detectTapGestures { offset ->
                    if (canTap) {
                        mapLiveViewTap(
                            tapX = offset.x,
                            tapY = offset.y,
                            containerWidth = size.width.toFloat(),
                            containerHeight = size.height.toFloat(),
                            sourceAspectRatio = sourceAspectRatio,
                        )?.let { point ->
                            when (tapAction) {
                                LiveViewTapAction.FOCUS -> actions.tapFocus(point.x, point.y)
                                LiveViewTapAction.WHITE_BALANCE -> actions.clickWhiteBalance(point.x, point.y)
                                null -> Unit
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.previewMode -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painterResource(LucideR.drawable.lucide_ic_camera),
                    contentDescription = null,
                    tint = AppAccent,
                    modifier = Modifier.size(40.dp).cameraControlRotation(),
                )
                Text(
                    stringResource(R.string.offline_preview),
                    color = AppText,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.cameraControlRotation(),
                )
                Text(
                    stringResource(R.string.offline_preview_hint),
                    color = AppSubtleText,
                    modifier = Modifier.padding(horizontal = 24.dp).cameraControlRotation(),
                )
            }
            state.nativeLiveViewSession != null -> NativeRtpLiveView(
                session = state.nativeLiveViewSession,
                modifier = Modifier.fillMaxSize(),
            )
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

        if (state.status?.recording == true) RecordingIndicator(Modifier.align(Alignment.CenterStart).padding(12.dp))
        if (state.supports(CameraFeature.CLICK_WHITE_BALANCE)) {
            val focusAvailable = state.supports(CameraFeature.TAP_FOCUS)
            val description = stringResource(
                if (tapAction == LiveViewTapAction.WHITE_BALANCE) {
                    R.string.tap_action_white_balance
                } else {
                    R.string.tap_action_focus
                }
            )
            ToolIconButton(
                icon = if (tapAction == LiveViewTapAction.WHITE_BALANCE) {
                    LucideR.drawable.lucide_ic_pipette
                } else {
                    LucideR.drawable.lucide_ic_focus
                },
                description = description,
                onClick = {
                    actions.setLiveViewTapAction(
                        if (tapAction == LiveViewTapAction.WHITE_BALANCE && focusAvailable) {
                            LiveViewTapAction.FOCUS
                        } else {
                            LiveViewTapAction.WHITE_BALANCE
                        }
                    )
                },
                enabled = !state.busy,
                tint = if (tapAction == LiveViewTapAction.WHITE_BALANCE) AppWarning else AppAccent,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(4.dp)),
            )
        }
        if (state.showGrid) GridOverlay(sourceAspectRatio)
        FocusIndicator(state.focusPoint, state.focusFeedback, sourceAspectRatio)
        if (state.captureFeedback == CaptureFeedback.SUCCESS) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.72f)))
    }
}

@Composable
private fun NativeRtpLiveView(
    session: NativeLiveViewSession,
    modifier: Modifier = Modifier,
) {
    val contentDescription = stringResource(R.string.live_view)
    val callback = remember(session) {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                session.attachSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                session.attachSurface(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                session.detachSurface(holder.surface)
            }
        }
    }
    var surfaceView by remember(session) { mutableStateOf<SurfaceView?>(null) }
    DisposableEffect(session) {
        onDispose {
            surfaceView?.holder?.let { holder ->
                if (holder.surface.isValid) session.detachSurface(holder.surface)
                holder.removeCallback(callback)
            }
            surfaceView = null
        }
    }
    AndroidView(
        factory = { context ->
            SurfaceView(context).also { view ->
                surfaceView = view
                view.holder.addCallback(callback)
            }
        },
        modifier = modifier.semantics { this.contentDescription = contentDescription },
    )
}

@Composable
private fun GridOverlay(sourceAspectRatio: Float) {
    val description = stringResource(R.string.composition_grid)
    Canvas(
        Modifier.fillMaxSize().semantics { contentDescription = description },
    ) {
        val content = fittedLiveViewRect(size.width, size.height, sourceAspectRatio)
        val color = Color.White.copy(alpha = 0.42f)
        val stroke = 1.dp.toPx()
        for (step in 1..2) {
            val x = content.left + content.width * step / 3f
            val y = content.top + content.height * step / 3f
            drawLine(color, Offset(x, content.top), Offset(x, content.top + content.height), stroke)
            drawLine(color, Offset(content.left, y), Offset(content.left + content.width, y), stroke)
        }
    }
}

@Composable
private fun FocusIndicator(point: FocusPoint?, feedback: FocusFeedback?, sourceAspectRatio: Float) {
    if (point == null) return
    val color = when (feedback) {
        FocusFeedback.SUCCESS -> AppSuccess
        FocusFeedback.FAILURE -> AppRecord
        else -> AppAccent
    }
    Canvas(Modifier.fillMaxSize()) {
        val display = mapFocusPointToDisplay(point, size.width, size.height, sourceAspectRatio)
        drawCircle(color, 28.dp.toPx(), Offset(display.x, display.y), style = Stroke(if (feedback == FocusFeedback.FOCUSING) 3.dp.toPx() else 2.dp.toPx()))
        drawLine(color, Offset(display.x - 36.dp.toPx(), display.y), Offset(display.x - 18.dp.toPx(), display.y), 2.dp.toPx())
        drawLine(color, Offset(display.x + 18.dp.toPx(), display.y), Offset(display.x + 36.dp.toPx(), display.y), 2.dp.toPx())
    }
}

@Composable
private fun RecordingIndicator(modifier: Modifier = Modifier) {
    var elapsedSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt) / 1_000L
            delay(1_000L)
        }
    }
    val elapsed = "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
    Row(
        modifier.background(Color(0xB8000000), RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(AppRecord, CircleShape))
        Text(
            stringResource(R.string.recording_time, elapsed),
            Modifier.cameraControlRotation(),
            color = AppText,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun ExposureStrip(state: CameraUiState, actions: CameraActions, modifier: Modifier = Modifier) {
    val exposure = state.status?.exposure
    val available = !state.isBusy(CameraOperation.SETTING)
    Row(modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        ExposureCell(stringResource(R.string.iso), exposure?.iso ?: "-", available && state.capabilities?.iso?.isNotEmpty() == true) { actions.openPicker(SettingPicker.ISO) }
        ExposureCell(stringResource(R.string.shutter), exposure?.shutter ?: "-", available && state.capabilities?.shutter?.isNotEmpty() == true) { actions.openPicker(SettingPicker.SHUTTER) }
        ExposureCell(stringResource(R.string.aperture), exposure?.aperture ?: "-", available && state.capabilities?.aperture?.isNotEmpty() == true) { actions.openPicker(SettingPicker.APERTURE) }
        ExposureCell(stringResource(R.string.white_balance), localizedCameraValue("whitebalance", exposure?.whiteBalance ?: "-"), available && state.capabilities?.whiteBalance?.isNotEmpty() == true) { actions.openPicker(SettingPicker.WHITE_BALANCE) }
    }
}

@Composable
fun LandscapeExposureGrid(state: CameraUiState, actions: CameraActions, modifier: Modifier = Modifier) {
    val exposure = state.status?.exposure
    val available = !state.isBusy(CameraOperation.SETTING)
    Column(modifier.fillMaxWidth().height(112.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            ExposureCell(stringResource(R.string.iso), exposure?.iso ?: "-", available && state.capabilities?.iso?.isNotEmpty() == true) { actions.openPicker(SettingPicker.ISO) }
            ExposureCell(stringResource(R.string.shutter), exposure?.shutter ?: "-", available && state.capabilities?.shutter?.isNotEmpty() == true) { actions.openPicker(SettingPicker.SHUTTER) }
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            ExposureCell(stringResource(R.string.aperture), exposure?.aperture ?: "-", available && state.capabilities?.aperture?.isNotEmpty() == true) { actions.openPicker(SettingPicker.APERTURE) }
            ExposureCell(stringResource(R.string.white_balance), localizedCameraValue("whitebalance", exposure?.whiteBalance ?: "-"), available && state.capabilities?.whiteBalance?.isNotEmpty() == true) { actions.openPicker(SettingPicker.WHITE_BALANCE) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ExposureCell(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxSize().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, Modifier.cameraControlRotation(), color = AppMutedText, maxLines = 1)
        Text(
            value,
            Modifier.cameraControlRotation(),
            color = if (enabled) AppText else AppMutedText,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    val operation = if (photo) CameraOperation.CAPTURE else CameraOperation.RECORDING
    val processing = state.isBusy(operation)
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(state.captureFeedback) {
        if (state.captureFeedback == CaptureFeedback.SUCCESS) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    Box(
        Modifier.size(76.dp)
            .background(AppBackground, CircleShape)
            .clickable(enabled = supported && !processing) { if (photo) actions.captureStill() else actions.toggleRecording() }
            .semantics { contentDescription = description; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        if (processing) {
            CircularProgressIndicator(Modifier.size(48.dp), color = color, strokeWidth = 3.dp)
        } else {
            Box(Modifier.size(if (photo) 58.dp else 52.dp).background(color, if (photo || state.status?.recording != true) CircleShape else RoundedCornerShape(8.dp)))
        }
    }
}

@Composable
fun ErrorBanner(error: String?, onDismiss: () -> Unit) {
    if (error == null) return
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().background(Color(0xFF512326)).padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(error, color = AppText, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        ToolIconButton(LucideR.drawable.lucide_ic_x, stringResource(R.string.dismiss), onDismiss)
    }
}
