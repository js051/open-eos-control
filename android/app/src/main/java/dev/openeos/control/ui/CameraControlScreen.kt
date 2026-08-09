package dev.openeos.control.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.openeos.control.R
import com.composables.icons.lucide.R as LucideR
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraSettingControl
import dev.openeos.control.data.CameraTemperatureStatus
import dev.openeos.control.data.FocusDriveDirection
import dev.openeos.control.data.FocusDriveStep
import dev.openeos.control.data.LiveViewSize
import dev.openeos.control.data.LiveViewSource
import java.util.Date
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CameraControlScreen(
    state: CameraUiState,
    actions: CameraActions,
) {
    Box(
        Modifier
            .fillMaxSize()
            .testTag("camera-control-root")
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .pointerInput(state.hudVisible) {
                var dragDistance = 0f
                detectVerticalDragGestures(
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragDistance += dragAmount
                    },
                    onDragEnd = {
                        if (abs(dragDistance) >= 48.dp.toPx()) {
                            actions.setHudVisible(!state.hudVisible)
                        }
                        dragDistance = 0f
                    },
                    onDragCancel = { dragDistance = 0f },
                )
            },
    ) {
        StableCameraControls(state, actions)
    }
    SettingSheets(state, actions)
}

@Composable
private fun StableCameraControls(state: CameraUiState, actions: CameraActions) {
    Box(Modifier.fillMaxSize()) {
        LiveViewFrame(state, actions, Modifier.fillMaxSize())
        if (state.hudVisible) {
            CameraOverlayHeader(state, actions)
            state.status?.temperature?.takeUnless(CameraTemperatureStatus::isNormal)?.let { temperature ->
                TemperatureStatusBanner(
                    temperature = temperature,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = CAMERA_OVERLAY_HEADER_HEIGHT + 8.dp),
                )
            }
            if (!state.supports(activeCaptureFeature(state))) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = CAMERA_OVERLAY_HEADER_HEIGHT,
                            bottom = cameraHudHeight(),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    CaptureCapabilityWarning(state = state)
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xE6101214)),
            ) {
                ExposureStrip(state, actions)
                CaptureBar(state, actions)
            }
        } else {
            ToolIconButton(
                LucideR.drawable.lucide_ic_eye,
                stringResource(R.string.show_hud),
                { actions.setHudVisible(true) },
                Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

@Composable
private fun TemperatureStatusBanner(
    temperature: CameraTemperatureStatus,
    modifier: Modifier = Modifier,
) {
    val messages = buildList {
        if (temperature.temperatureWarning) add(stringResource(R.string.camera_temperature_warning))
        if (temperature.frameRateReduced) add(stringResource(R.string.temperature_frame_rate_reduced))
        if (!temperature.liveViewAllowed) add(stringResource(R.string.temperature_live_view_unavailable))
        if (!temperature.stillCaptureAllowed) add(stringResource(R.string.temperature_shutter_unavailable))
        if (!temperature.movieRecordingAllowed) add(stringResource(R.string.temperature_movie_recording_restricted))
        if (temperature.stillQualityWarning) add(stringResource(R.string.temperature_still_quality_warning))
    }.ifEmpty { listOf(stringResource(R.string.camera_temperature_warning)) }
    val message = messages.joinToString(separator = " · ")
    CameraReadableSlot(
        width = 344.dp,
        height = 64.dp,
        modifier = modifier
            .testTag("temperature-status-banner")
            .semantics { contentDescription = message },
        animateRotation = false,
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.82f),
            shape = RoundedCornerShape(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painterResource(LucideR.drawable.lucide_ic_triangle_alert),
                    contentDescription = null,
                    tint = AppWarning,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = message,
                    color = AppWarning,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CaptureBar(state: CameraUiState, actions: CameraActions) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.fillMaxWidth().height(CAPTURE_CONTROL_HEIGHT).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ToolIconButton(LucideR.drawable.lucide_ic_settings, stringResource(R.string.more_settings), { actions.openPicker(SettingPicker.MORE) })
            CaptureButton(state, actions)
            LiveViewFpsButton(state, { actions.openPicker(SettingPicker.LIVE_VIEW) })
        }
        CaptureModeSelector(state, actions)
    }
}

private val CAPTURE_CONTROL_HEIGHT = 88.dp
private val CAPTURE_MODE_SELECTOR_HEIGHT = 56.dp

private fun activeCaptureFeature(state: CameraUiState): CameraFeature = when {
    state.bulbMode -> CameraFeature.BULB_EXPOSURE
    state.captureMode == CaptureMode.PHOTO -> CameraFeature.STILL_CAPTURE
    else -> CameraFeature.VIDEO_RECORDING
}

@Composable
private fun CaptureCapabilityWarning(
    state: CameraUiState,
    modifier: Modifier = Modifier,
) {
    val message = stringResource(
        when {
            state.bulbMode -> R.string.bulb_not_supported
            state.captureMode == CaptureMode.PHOTO -> R.string.capture_not_supported
            else -> R.string.recording_not_supported
        },
    )
    val configuration = LocalConfiguration.current
    val rotationQuadrant = cameraRotationQuadrant(LocalCameraControlTargetRotation.current)
    val compact = rotationQuadrant % 2 == 1
    val warningWidth = if (compact) 320.dp else 304.dp
    val warningHeight = if (compact) 108.dp else if (configuration.fontScale >= 1.3f) 176.dp else 144.dp
    var fontSize by remember(message, configuration.fontScale, rotationQuadrant) {
        mutableStateOf(14.sp)
    }
    var hasVisualOverflow by remember(message, configuration.fontScale, rotationQuadrant) {
        mutableStateOf(false)
    }
    CameraReadableSlot(
        width = warningWidth,
        height = warningHeight,
        modifier = modifier
            .testTag("capability-warning-rotation")
            .semantics { contentDescription = message },
        animateRotation = false,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("capability-warning-surface"),
            shape = RoundedCornerShape(6.dp),
            color = Color.Black.copy(alpha = 0.78f),
        ) {
            val contentModifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)
            if (compact) {
                Row(
                    modifier = contentModifier,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                ) {
                    Icon(
                        painterResource(LucideR.drawable.lucide_ic_triangle_alert),
                        contentDescription = null,
                        tint = AppWarning,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = message,
                        color = AppWarning,
                        fontSize = fontSize,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Clip,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(
                                if (hasVisualOverflow) {
                                    "capability-warning-message-overflow"
                                } else {
                                    "capability-warning-message"
                                },
                            ),
                        onTextLayout = { result ->
                            hasVisualOverflow = result.hasVisualOverflow
                            if (result.hasVisualOverflow && fontSize > 11.sp) {
                                fontSize = (fontSize.value - 0.5f).coerceAtLeast(11f).sp
                            }
                        },
                    )
                }
            } else {
                Column(
                    modifier = contentModifier,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        painterResource(LucideR.drawable.lucide_ic_triangle_alert),
                        contentDescription = null,
                        tint = AppWarning,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = message,
                        color = AppWarning,
                        fontSize = fontSize,
                        lineHeight = fontSize * 1.2f,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 5,
                        overflow = TextOverflow.Clip,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag(
                            if (hasVisualOverflow) {
                                "capability-warning-message-overflow"
                            } else {
                                "capability-warning-message"
                            },
                        ),
                        onTextLayout = { result ->
                            hasVisualOverflow = result.hasVisualOverflow
                            if (result.hasVisualOverflow && fontSize > 11.sp) {
                                fontSize = (fontSize.value - 0.5f).coerceAtLeast(11f).sp
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureModeSelector(state: CameraUiState, actions: CameraActions) {
    val enabled = captureModeSwitchEnabled(state)
    Box(
        Modifier
            .fillMaxWidth()
            .height(CAPTURE_MODE_SELECTOR_HEIGHT)
            .testTag("capture-mode-selector"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .width(208.dp)
                .height(48.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CaptureModeOption(
                label = stringResource(R.string.photo),
                mode = CaptureMode.PHOTO,
                selected = state.captureMode == CaptureMode.PHOTO,
                enabled = enabled,
                onSelect = actions.setCaptureMode,
                modifier = Modifier.weight(1f),
            )
            CaptureModeOption(
                label = stringResource(R.string.video),
                mode = CaptureMode.VIDEO,
                selected = state.captureMode == CaptureMode.VIDEO,
                enabled = enabled,
                onSelect = actions.setCaptureMode,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CaptureModeOption(
    label: String,
    mode: CaptureMode,
    selected: Boolean,
    enabled: Boolean,
    onSelect: (CaptureMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedColor = if (mode == CaptureMode.VIDEO) AppRecord else AppAccent
    Box(
        modifier
            .fillMaxSize()
            .testTag("capture-mode-${mode.name}")
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.Tab,
                onClick = { onSelect(mode) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        CameraRotatingSlot(Modifier.fillMaxSize()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    label,
                    color = when {
                        !enabled -> AppMutedText
                        selected -> selectedColor
                        else -> AppSubtleText
                    },
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                )
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .width(20.dp)
                        .height(3.dp)
                        .testTag("capture-mode-indicator-${mode.name}")
                        .background(
                            if (selected && enabled) selectedColor else Color.Transparent,
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
    }
}

internal fun cameraHudHeight(): Dp {
    return 84.dp + CAPTURE_CONTROL_HEIGHT + CAPTURE_MODE_SELECTOR_HEIGHT
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingSheets(
    state: CameraUiState,
    actions: CameraActions,
) {
    when (state.activeSettingPicker) {
        SettingPicker.ISO,
        SettingPicker.SHUTTER,
        SettingPicker.APERTURE,
        SettingPicker.WHITE_BALANCE,
        -> ExposureSettingsSheet(state, actions)
        SettingPicker.LIVE_VIEW -> LiveViewSettingsSheet(state, actions)
        SettingPicker.MONITOR -> MonitoringAssistSheet(state, actions)
        SettingPicker.MORE -> MoreSettingsSheet(state, actions)
        SettingPicker.LANGUAGE -> Unit
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExposureSettingsSheet(state: CameraUiState, actions: CameraActions) {
    val picker = state.activeSettingPicker ?: return
    val title: String
    val valueKey: String
    val values: List<String>
    val current: String?
    val onSelect: (String) -> Unit
    when (picker) {
        SettingPicker.ISO -> {
            title = stringResource(R.string.iso)
            valueKey = "iso"
            values = state.capabilities?.iso.orEmpty()
            current = state.status?.exposure?.iso
            onSelect = actions.setIso
        }
        SettingPicker.SHUTTER -> {
            title = stringResource(R.string.shutter)
            valueKey = "shutter"
            values = state.capabilities?.shutter.orEmpty()
            current = state.status?.exposure?.shutter
            onSelect = actions.setShutter
        }
        SettingPicker.APERTURE -> {
            title = stringResource(R.string.aperture)
            valueKey = "aperture"
            values = state.capabilities?.aperture.orEmpty()
            current = state.status?.exposure?.aperture
            onSelect = actions.setAperture
        }
        SettingPicker.WHITE_BALANCE -> {
            title = stringResource(R.string.white_balance)
            valueKey = "whitebalance"
            values = state.capabilities?.whiteBalance.orEmpty()
            current = state.status?.exposure?.whiteBalance
            onSelect = actions.setWhiteBalance
        }
        else -> return
    }
    CameraSettingsSurface(
        onDismissRequest = actions.closePicker,
    ) {
        ExposurePickerTabs(state, picker, actions)
        key(picker) {
            ExposureDial(title, valueKey, values, current, state.isBusy(CameraOperation.SETTING), onSelect)
        }
    }
}

@Composable
private fun ExposurePickerTabs(state: CameraUiState, selected: SettingPicker, actions: CameraActions) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExposurePickerTab(stringResource(R.string.iso), SettingPicker.ISO, selected, state.capabilities?.iso?.isNotEmpty() == true, actions)
        ExposurePickerTab(stringResource(R.string.shutter), SettingPicker.SHUTTER, selected, state.capabilities?.shutter?.isNotEmpty() == true, actions)
        ExposurePickerTab(stringResource(R.string.aperture), SettingPicker.APERTURE, selected, state.capabilities?.aperture?.isNotEmpty() == true, actions)
        ExposurePickerTab(stringResource(R.string.white_balance), SettingPicker.WHITE_BALANCE, selected, state.capabilities?.whiteBalance?.isNotEmpty() == true, actions)
        ToolIconButton(LucideR.drawable.lucide_ic_x, stringResource(R.string.dismiss), actions.closePicker)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ExposurePickerTab(
    label: String,
    picker: SettingPicker,
    selected: SettingPicker,
    enabled: Boolean,
    actions: CameraActions,
) {
    Box(
        Modifier
            .weight(1f)
            .height(44.dp)
            .testTag("exposure-picker-${picker.name}")
            .background(if (picker == selected) AppBorder else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled) { actions.openPicker(picker) }
            .semantics { role = Role.Tab },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) AppText else AppMutedText, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ExposureDial(
    title: String,
    valueKey: String,
    values: List<String>,
    current: String?,
    isApplying: Boolean,
    onSelect: (String) -> Unit,
) {
    if (values.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 56.dp)
        ) {
            Text(title, color = AppText, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.no_settings), color = AppSubtleText, modifier = Modifier.padding(top = 16.dp))
        }
        return
    }

    val initialIndex = values.indexOf(current).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val scope = rememberCoroutineScope()
    var selectedIndex by remember(values, current) { mutableIntStateOf(initialIndex) }

    LaunchedEffect(isApplying, current, values) {
        if (!isApplying) {
            val confirmedIndex = values.indexOf(current)
            if (confirmedIndex >= 0 && confirmedIndex != selectedIndex) {
                selectedIndex = confirmedIndex
                listState.animateScrollToItem(confirmedIndex)
            }
        }
    }

    LaunchedEffect(listState, values) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { scrolling -> !scrolling }
            .collect {
                val viewportCenter = (listState.layoutInfo.viewportStartOffset + listState.layoutInfo.viewportEndOffset) / 2
                val centered = listState.layoutInfo.visibleItemsInfo.minByOrNull { item ->
                    abs((item.offset + item.size / 2) - viewportCenter)
                } ?: return@collect
                if (centered.index != selectedIndex) {
                    selectedIndex = centered.index
                    onSelect(values[centered.index])
                }
            }
    }

    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 52.dp)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = AppMutedText)
                Text(localizedCameraValue(valueKey, values[selectedIndex]), color = AppText, fontWeight = FontWeight.Bold)
            }
            if (isApplying) CircularProgressIndicator(Modifier.size(24.dp), color = AppAccent, strokeWidth = 2.dp)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(104.dp)) {
            val itemWidth = 112.dp
            val edgePadding = ((maxWidth - itemWidth) / 2).coerceAtLeast(0.dp)
            LazyRow(
                modifier = Modifier.selectableGroup(),
                state = listState,
                contentPadding = PaddingValues(horizontal = edgePadding),
                flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
                userScrollEnabled = !isApplying,
            ) {
                items(values.size, key = { values[it] }) { index ->
                    val selected = index == selectedIndex
                    Box(
                        Modifier
                            .width(itemWidth)
                            .height(88.dp)
                            .testTag("exposure-option-$index")
                            .selectable(
                                selected = selected,
                                enabled = !isApplying,
                                role = Role.RadioButton,
                                onClick = {
                                    selectedIndex = index
                                    onSelect(values[index])
                                    scope.launch { listState.animateScrollToItem(index) }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            localizedCameraValue(valueKey, values[index]),
                            color = if (selected) AppText else AppMutedText,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(itemWidth)
                    .height(56.dp)
                    .border(2.dp, AppAccent, RoundedCornerShape(6.dp)),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveViewSettingsSheet(state: CameraUiState, actions: CameraActions) {
    val minFps = state.capabilities?.liveView?.minFps ?: MIN_LIVE_VIEW_FPS
    val maxFps = state.capabilities?.liveView?.maxFps ?: MAX_LIVE_VIEW_FPS
    var pendingFps by remember(state.liveViewFrameRateFps) {
        mutableFloatStateOf(state.liveViewFrameRateFps.toFloat())
    }
    val displayedFps = pendingFps.roundToInt().coerceIn(minFps, maxFps)
    val audioMonitorDescription = stringResource(R.string.rtp_audio_monitor_description)
    CameraSettingsSurface(
        onDismissRequest = actions.closePicker,
    ) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp)) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SettingsSheetTitle(stringResource(R.string.live_view_settings), actions.closePicker)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.auto_refresh), color = AppText, modifier = Modifier.weight(1f))
                    Switch(state.liveViewAutoRefresh, actions.setAutoRefresh)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.composition_grid), color = AppText, modifier = Modifier.weight(1f))
                    Switch(state.showGrid, actions.setGridVisible)
                }
                Button(
                    onClick = { actions.openPicker(SettingPicker.MONITOR) },
                    colors = ButtonDefaults.buttonColors(containerColor = AppSurfaceHigh, contentColor = AppText),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_eye), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.monitoring_assists))
                }
                Text(stringResource(R.string.live_view_source), color = AppText, fontWeight = FontWeight.SemiBold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.capabilities?.liveView?.sources.orEmpty()) { source ->
                        Box(
                            Modifier
                                .height(48.dp)
                                .background(
                                    if (source == state.liveViewSource) AppAccent else AppSurfaceHigh,
                                    RoundedCornerShape(6.dp),
                                )
                                .clickable(enabled = !state.isBusy(CameraOperation.LIVE_VIEW)) {
                                    actions.setLiveViewSource(source)
                                }
                                .padding(horizontal = 18.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                liveViewSourceLabel(source),
                                color = if (source == state.liveViewSource) AppBackground else AppText,
                            )
                        }
                    }
                }
                if (
                    state.liveViewSource == LiveViewSource.CCAPI_RTP &&
                    state.liveViewAudioStatus.advertised
                ) {
                    val audio = state.liveViewAudioStatus
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.rtp_audio_monitor),
                                color = AppText,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                stringResource(
                                    if (audio.available) {
                                        R.string.rtp_audio_monitor_hint
                                    } else {
                                        R.string.rtp_audio_monitor_unavailable
                                    }
                                ),
                                color = if (audio.available) AppSubtleText else AppWarning,
                            )
                        }
                        Switch(
                            checked = audio.enabled,
                            onCheckedChange = actions.setRtpAudioEnabled,
                            enabled = audio.available,
                            modifier = Modifier
                                .testTag("rtp-audio-toggle")
                                .semantics {
                                    contentDescription = audioMonitorDescription
                                },
                        )
                    }
                    audio.error?.let { error ->
                        Text(error, color = AppWarning, modifier = Modifier.testTag("rtp-audio-error"))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                if (state.liveViewSource == LiveViewSource.CCAPI_RTP) {
                                    R.string.rtp_render_frame_rate
                                } else {
                                    R.string.live_view_frame_rate
                                }
                            ),
                            color = AppText,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.fps_requested_observed, displayedFps, state.liveViewDiagnostics.observedFps),
                            color = AppSubtleText,
                        )
                    }
                    Text(stringResource(R.string.fps_value, displayedFps), color = AppAccent, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ToolIconButton(
                        LucideR.drawable.lucide_ic_minus,
                        stringResource(R.string.decrease_fps),
                        {
                            pendingFps = (displayedFps - 1).coerceAtLeast(minFps).toFloat()
                            actions.setFps(pendingFps.roundToInt())
                        },
                        enabled = displayedFps > minFps,
                    )
                    Slider(
                        value = pendingFps,
                        onValueChange = { pendingFps = it },
                        onValueChangeFinished = { actions.setFps(displayedFps) },
                        valueRange = minFps.toFloat()..maxFps.toFloat(),
                        steps = (maxFps - minFps - 1).coerceAtLeast(0),
                        modifier = Modifier.weight(1f),
                    )
                    ToolIconButton(
                        LucideR.drawable.lucide_ic_plus,
                        stringResource(R.string.increase_fps),
                        {
                            pendingFps = (displayedFps + 1).coerceAtMost(maxFps).toFloat()
                            actions.setFps(pendingFps.roundToInt())
                        },
                        enabled = displayedFps < maxFps,
                    )
                }
                if (state.liveViewSource != LiveViewSource.CCAPI_RTP) {
                    Text(stringResource(R.string.size), color = AppText)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.capabilities?.liveView?.sizes.orEmpty()) { size ->
                            Box(
                                Modifier.height(48.dp).background(if (size == state.liveViewSize) AppAccent else AppSurfaceHigh, RoundedCornerShape(6.dp)).clickable { actions.setLiveViewSize(size) }.padding(horizontal = 18.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(liveViewSizeLabel(size), color = if (size == state.liveViewSize) AppBackground else AppText) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonitoringAssistSheet(state: CameraUiState, actions: CameraActions) {
    CameraSettingsSurface(onDismissRequest = actions.closePicker) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSheetTitle(stringResource(R.string.monitoring_assists), actions.closePicker)
            MonitoringAssistSettings(state, actions)
        }
    }
}

@Composable
private fun MonitoringAssistSettings(state: CameraUiState, actions: CameraActions) {
    val settings = state.monitorSettings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pixelAnalysisAvailable = !state.previewMode &&
        state.liveViewSource != LiveViewSource.CCAPI_RTP &&
        state.nativeLiveViewSession == null
    val lutLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { readCubeLutDocument(context, uri) }
                }.fold(
                    onSuccess = { (name, text) -> actions.importCubeLut(name, text) },
                    onFailure = { actions.reportCubeLutError(it.message ?: it::class.java.simpleName) },
                )
            }
        }
    }
    if (!pixelAnalysisAvailable) {
        Text(stringResource(R.string.monitoring_assists_rtp_unavailable), color = AppWarning)
    }
    MonitorSwitchRow(
        label = stringResource(R.string.histogram),
        checked = settings.histogramVisible,
        enabled = pixelAnalysisAvailable,
        onCheckedChange = actions.setHistogramVisible,
    )
    MonitorSwitchRow(
        label = stringResource(R.string.luma_waveform),
        checked = settings.waveformVisible,
        enabled = pixelAnalysisAvailable,
        onCheckedChange = actions.setWaveformVisible,
    )
    Column(
        Modifier.fillMaxWidth().testTag("monitor-lut-options"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.lut_preview), color = if (pixelAnalysisAvailable) AppText else AppMutedText)
        Text(
            settings.cubeLut?.let { stringResource(R.string.cube_lut_summary, it.name, it.size) }
                ?: stringResource(R.string.no_cube_lut),
            color = AppMutedText,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    lutLauncher.launch(arrayOf("text/plain", "application/octet-stream", "application/x-cube"))
                },
                enabled = pixelAnalysisAvailable,
                modifier = Modifier.height(48.dp),
            ) {
                Icon(
                    painterResource(LucideR.drawable.lucide_ic_download),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.load_cube_lut))
            }
            if (settings.cubeLut != null) {
                ToolIconButton(
                    icon = LucideR.drawable.lucide_ic_trash_2,
                    description = stringResource(R.string.remove_cube_lut),
                    onClick = actions.clearCubeLut,
                    enabled = pixelAnalysisAvailable,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
    }
    MonitorChoiceRow(
        label = stringResource(R.string.zebra),
        testTag = "monitor-zebra-options",
        values = listOf(null, 70, 75, 80, 85, 90, 95, 100),
        selected = settings.zebraThresholdPercent,
        enabled = pixelAnalysisAvailable,
        labelFor = { value -> value?.let { stringResource(R.string.zebra_threshold, it) } ?: stringResource(R.string.off) },
        onSelect = actions.setZebraThreshold,
    )
    MonitorSwitchRow(
        label = stringResource(R.string.false_color),
        checked = settings.falseColorEnabled,
        enabled = pixelAnalysisAvailable,
        onCheckedChange = actions.setFalseColorEnabled,
    )
    MonitorSwitchRow(
        label = stringResource(R.string.focus_peaking),
        checked = settings.focusPeakingEnabled,
        enabled = pixelAnalysisAvailable,
        onCheckedChange = actions.setFocusPeakingEnabled,
    )
    MonitorChoiceRow(
        label = stringResource(R.string.frame_guide),
        testTag = "monitor-frame-guide-options",
        values = LiveViewFrameGuide.entries,
        selected = settings.frameGuide,
        labelFor = { guide -> frameGuideLabel(guide) },
        onSelect = actions.setFrameGuide,
    )
    MonitorSwitchRow(
        label = stringResource(R.string.safe_area),
        checked = settings.safeAreaVisible,
        onCheckedChange = actions.setSafeAreaVisible,
    )
    MonitorChoiceRow(
        label = stringResource(R.string.anamorphic_desqueeze),
        testTag = "monitor-desqueeze-options",
        values = LiveViewDesqueeze.entries,
        selected = settings.desqueeze,
        labelFor = { desqueeze -> desqueezeLabel(desqueeze) },
        onSelect = actions.setDesqueeze,
    )
}

private fun readCubeLutDocument(context: Context, uri: Uri): Pair<String, String> {
    val name = context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }?.takeIf(String::isNotBlank) ?: uri.lastPathSegment ?: "Imported LUT.cube"
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= MAX_CUBE_LUT_BYTES) {
                "3D LUT exceeds the ${MAX_CUBE_LUT_BYTES / (1024 * 1024)} MiB limit."
            }
        }
        output.toByteArray()
    } ?: error("The selected LUT could not be opened.")
    return name to bytes.toString(Charsets.UTF_8)
}

@Composable
private fun MonitorSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (enabled) AppText else AppMutedText, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun <T> MonitorChoiceRow(
    label: String,
    testTag: String,
    values: List<T>,
    selected: T,
    enabled: Boolean = true,
    labelFor: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = if (enabled) AppText else AppMutedText)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.testTag(testTag),
        ) {
            items(values) { value ->
                val selectedValue = value == selected
                val valueLabel = labelFor(value)
                Box(
                    Modifier
                        .height(48.dp)
                        .background(
                            if (selectedValue && enabled) AppAccent else AppSurfaceHigh,
                            RoundedCornerShape(6.dp),
                        )
                        .selectable(
                            selected = selectedValue,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onSelect(value) },
                        )
                        .padding(horizontal = 14.dp)
                        .semantics {
                            contentDescription = "$label, $valueLabel"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        valueLabel,
                        color = when {
                            !enabled -> AppMutedText
                            selectedValue -> AppBackground
                            else -> AppText
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun frameGuideLabel(guide: LiveViewFrameGuide): String = stringResource(
    when (guide) {
        LiveViewFrameGuide.OFF -> R.string.off
        LiveViewFrameGuide.RATIO_16_9 -> R.string.ratio_16_9
        LiveViewFrameGuide.RATIO_2_39 -> R.string.ratio_2_39
        LiveViewFrameGuide.RATIO_1_1 -> R.string.ratio_1_1
        LiveViewFrameGuide.RATIO_4_3 -> R.string.ratio_4_3
    }
)

@Composable
private fun desqueezeLabel(desqueeze: LiveViewDesqueeze): String = when (desqueeze) {
    LiveViewDesqueeze.OFF -> stringResource(R.string.off)
    else -> stringResource(R.string.desqueeze_value, desqueeze.horizontalScale)
}

@Composable
private fun liveViewSizeLabel(size: LiveViewSize): String = stringResource(
    when (size) {
        LiveViewSize.SMALL -> R.string.size_small
        LiveViewSize.MEDIUM -> R.string.size_medium
        LiveViewSize.LARGE -> R.string.size_large
    },
)

@Composable
private fun liveViewSourceLabel(source: LiveViewSource): String = stringResource(
    when (source) {
        LiveViewSource.CCAPI_RTP -> R.string.live_view_source_rtp
        LiveViewSource.CCAPI_MULTIPART -> R.string.live_view_source_multipart
        LiveViewSource.CCAPI_JPEG_POLLING -> R.string.live_view_source_jpeg
        LiveViewSource.USB_PTP_PREVIEW -> R.string.live_view_source_usb
        LiveViewSource.DESKTOP_BRIDGE_STREAM -> R.string.live_view_source_bridge
        LiveViewSource.SIMULATOR_FRAME -> R.string.live_view_source_simulator
        LiveViewSource.AUTO -> R.string.live_view_source_auto
    }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreSettingsSheet(state: CameraUiState, actions: CameraActions) {
    val settings = settingsForMode(state.capabilities?.advancedSettings.orEmpty(), state.captureMode)
    var showSleepConfirmation by remember { mutableStateOf(false) }
    var showSensorCleaningConfirmation by remember { mutableStateOf(false) }
    var showDirectoryCreation by remember { mutableStateOf(false) }
    var directoryName by remember { mutableStateOf("") }
    var sensorCleaningAutoPowerOff by remember { mutableStateOf(false) }
    if (showSensorCleaningConfirmation) {
        AlertDialog(
            onDismissRequest = { showSensorCleaningConfirmation = false },
            title = { Text(stringResource(R.string.sensor_cleaning_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.sensor_cleaning_confirm_message))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .toggleable(
                                value = sensorCleaningAutoPowerOff,
                                role = Role.Switch,
                                onValueChange = { sensorCleaningAutoPowerOff = it },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.sensor_cleaning_power_off),
                            modifier = Modifier.weight(1f),
                            color = AppText,
                        )
                        Switch(
                            checked = sensorCleaningAutoPowerOff,
                            onCheckedChange = null,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSensorCleaningConfirmation = false
                        actions.cleanSensor(sensorCleaningAutoPowerOff)
                    },
                    modifier = Modifier.testTag("sensor-cleaning-confirm"),
                ) {
                    Text(stringResource(R.string.clean_now), color = AppAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSensorCleaningConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            containerColor = AppSurface,
            titleContentColor = AppText,
            textContentColor = AppSubtleText,
        )
    }
    if (showSleepConfirmation) {
        AlertDialog(
            onDismissRequest = { showSleepConfirmation = false },
            title = { Text(stringResource(R.string.camera_sleep_confirm_title)) },
            text = { Text(stringResource(R.string.camera_sleep_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSleepConfirmation = false
                        actions.sleepCamera()
                    },
                    modifier = Modifier.testTag("camera-sleep-confirm"),
                ) {
                    Text(stringResource(R.string.sleep_now), color = AppRecord)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSleepConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            containerColor = AppSurface,
            titleContentColor = AppText,
            textContentColor = AppSubtleText,
        )
    }
    if (showDirectoryCreation) {
        val validName = directoryName.isEmpty() || Regex("^[A-Z0-9_]{5}$").matches(directoryName)
        AlertDialog(
            onDismissRequest = { showDirectoryCreation = false },
            title = { Text(stringResource(R.string.create_capture_directory)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.create_capture_directory_hint))
                    OutlinedTextField(
                        value = directoryName,
                        onValueChange = { raw ->
                            directoryName = raw.uppercase()
                                .filter { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_" }
                                .take(5)
                        },
                        singleLine = true,
                        label = { Text(stringResource(R.string.directory_name)) },
                        supportingText = { Text(stringResource(R.string.directory_name_rule)) },
                        isError = !validName,
                        modifier = Modifier.fillMaxWidth().testTag("directory-name"),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDirectoryCreation = false
                        actions.createDirectory(directoryName)
                    },
                    enabled = validName && !state.isBusy(CameraOperation.DIRECTORY),
                    modifier = Modifier.testTag("create-directory-confirm"),
                ) { Text(stringResource(R.string.create), color = AppAccent) }
            },
            dismissButton = {
                TextButton(onClick = { showDirectoryCreation = false }) { Text(stringResource(R.string.cancel)) }
            },
            containerColor = AppSurface,
            titleContentColor = AppText,
            textContentColor = AppSubtleText,
        )
    }
    CameraSettingsSurface(
        onDismissRequest = actions.closePicker,
    ) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp)) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SettingsSheetTitle(stringResource(R.string.more_settings), actions.closePicker)
                if (state.supports(CameraFeature.CLICK_WHITE_BALANCE)) {
                    LiveViewTapActionControls(state, actions)
                }
                if (state.supports(CameraFeature.AUTOFOCUS)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.focus_with_shutter), color = AppText, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.focus_with_shutter_hint), color = AppSubtleText)
                        }
                        Button(
                            onClick = actions.autofocus,
                            enabled = !state.isBusy(CameraOperation.FOCUS),
                            colors = ButtonDefaults.buttonColors(containerColor = AppSurfaceHigh, contentColor = AppText),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(48.dp).testTag("autofocus"),
                        ) {
                            Icon(painterResource(LucideR.drawable.lucide_ic_focus), null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.af_on))
                        }
                    }
                }
                if (state.supports(CameraFeature.SHUTTER_HALF_PRESS)) {
                    val halfPressDescription = stringResource(R.string.half_press_shutter_action)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.half_press_shutter), color = AppText, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.half_press_shutter_hint), color = AppSubtleText)
                        }
                        Button(
                            onClick = actions.halfPressShutter,
                            enabled = !state.isBusy(CameraOperation.FOCUS),
                            colors = ButtonDefaults.buttonColors(containerColor = AppSurfaceHigh, contentColor = AppText),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("shutter-half-press")
                                .semantics { contentDescription = halfPressDescription },
                        ) {
                            Icon(painterResource(LucideR.drawable.lucide_ic_camera), null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.half_press))
                        }
                    }
                }
                if (state.supports(CameraFeature.FOCUS_DRIVE)) {
                    ManualFocusDriveControls(state, actions)
                }
                if (state.supports(CameraFeature.CAMERA_CLOCK_SYNC)) {
                    val context = LocalContext.current
                    val syncedAt = state.lastClockSyncAtMillis?.let {
                        DateFormat.getTimeFormat(context).format(Date(it))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.sync_camera_clock),
                                color = AppText,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                syncedAt?.let { stringResource(R.string.camera_clock_synced_at, it) }
                                    ?: stringResource(R.string.sync_camera_clock_hint),
                                color = if (syncedAt == null) AppSubtleText else AppSuccess,
                            )
                        }
                        Button(
                            onClick = actions.syncCameraClock,
                            enabled = !state.isBusy(CameraOperation.CLOCK),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppSurfaceHigh,
                                contentColor = AppText,
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("sync-camera-clock"),
                        ) {
                            Icon(
                                painterResource(LucideR.drawable.lucide_ic_clock),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.sync_now))
                        }
                    }
                }
                if (state.supports(CameraFeature.DIRECTORY_CONTROL)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.create_capture_directory),
                                color = AppText,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                state.lastCreatedDirectoryName?.let {
                                    stringResource(R.string.directory_created, it)
                                } ?: stringResource(R.string.create_capture_directory_hint),
                                color = if (state.lastCreatedDirectoryName == null) AppSubtleText else AppSuccess,
                            )
                        }
                        Button(
                            onClick = {
                                directoryName = ""
                                showDirectoryCreation = true
                            },
                            enabled = !state.previewMode && !state.busy,
                            colors = ButtonDefaults.buttonColors(containerColor = AppSurfaceHigh, contentColor = AppText),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(48.dp).testTag("create-directory"),
                        ) {
                            Text(stringResource(R.string.create))
                        }
                    }
                }
                if (state.supports(CameraFeature.SENSOR_CLEANING)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.sensor_cleaning),
                                color = AppText,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(stringResource(R.string.sensor_cleaning_hint), color = AppSubtleText)
                        }
                        Button(
                            onClick = {
                                sensorCleaningAutoPowerOff = false
                                showSensorCleaningConfirmation = true
                            },
                            enabled = !state.previewMode &&
                                state.status?.recording != true &&
                                !state.bulbExposureActive &&
                                !state.busy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppSurfaceHigh,
                                contentColor = AppAccent,
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("sensor-cleaning"),
                        ) {
                            Icon(
                                painterResource(LucideR.drawable.lucide_ic_refresh_cw),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.clean_now))
                        }
                    }
                }
                if (state.supports(CameraFeature.CAMERA_SLEEP)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.camera_sleep),
                                color = AppText,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(stringResource(R.string.camera_sleep_hint), color = AppSubtleText)
                        }
                        Button(
                            onClick = { showSleepConfirmation = true },
                            enabled = !state.previewMode &&
                                state.status?.recording != true &&
                                !state.bulbExposureActive &&
                                !state.busy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppSurfaceHigh,
                                contentColor = AppRecord,
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("camera-sleep"),
                        ) {
                            Icon(
                                painterResource(LucideR.drawable.lucide_ic_power),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.sleep_now))
                        }
                    }
                }
                if (
                    settings.isEmpty() &&
                    !state.supports(CameraFeature.CLICK_WHITE_BALANCE) &&
                    !state.supports(CameraFeature.AUTOFOCUS) &&
                    !state.supports(CameraFeature.SHUTTER_HALF_PRESS) &&
                    !state.supports(CameraFeature.FOCUS_DRIVE) &&
                    !state.supports(CameraFeature.CAMERA_CLOCK_SYNC) &&
                    !state.supports(CameraFeature.DIRECTORY_CONTROL) &&
                    !state.supports(CameraFeature.SENSOR_CLEANING) &&
                    !state.supports(CameraFeature.CAMERA_SLEEP)
                ) {
                    Text(stringResource(R.string.no_settings), color = AppSubtleText)
                }
                settings.forEach { setting -> AdvancedSettingRow(setting, actions) }
            }
        }
    }
}

@Composable
private fun SettingsSheetTitle(title: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), color = AppText, fontWeight = FontWeight.Bold)
        ToolIconButton(LucideR.drawable.lucide_ic_x, stringResource(R.string.dismiss), onDismiss)
    }
}

@Composable
private fun LiveViewTapActionControls(state: CameraUiState, actions: CameraActions) {
    val selectedAction = when {
        state.liveViewTapAction == LiveViewTapAction.WHITE_BALANCE &&
            state.supports(CameraFeature.CLICK_WHITE_BALANCE) -> LiveViewTapAction.WHITE_BALANCE
        state.supports(CameraFeature.TAP_FOCUS) -> LiveViewTapAction.FOCUS
        else -> LiveViewTapAction.WHITE_BALANCE
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.live_view_tap_action), color = AppText, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.supports(CameraFeature.TAP_FOCUS)) {
                Button(
                    onClick = { actions.setLiveViewTapAction(LiveViewTapAction.FOCUS) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedAction == LiveViewTapAction.FOCUS) AppAccent else AppSurfaceHigh,
                        contentColor = if (selectedAction == LiveViewTapAction.FOCUS) AppBackground else AppText,
                    ),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f).height(64.dp).testTag("tap-action-focus"),
                ) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_focus), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.tap_action_focus),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Button(
                onClick = { actions.setLiveViewTapAction(LiveViewTapAction.WHITE_BALANCE) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedAction == LiveViewTapAction.WHITE_BALANCE) AppWarning else AppSurfaceHigh,
                    contentColor = if (selectedAction == LiveViewTapAction.WHITE_BALANCE) AppBackground else AppText,
                ),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f).height(64.dp).testTag("tap-action-white-balance"),
            ) {
                Icon(painterResource(LucideR.drawable.lucide_ic_pipette), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.tap_action_white_balance),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ManualFocusDriveControls(state: CameraUiState, actions: CameraActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.manual_focus_drive), color = AppText, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.manual_focus_drive_hint), color = AppSubtleText)
        FocusDriveDirectionRow(
            label = stringResource(R.string.focus_nearer),
            icon = LucideR.drawable.lucide_ic_arrow_left,
            direction = FocusDriveDirection.NEAR,
            enabled = !state.isBusy(CameraOperation.FOCUS),
            actions = actions,
        )
        FocusDriveDirectionRow(
            label = stringResource(R.string.focus_farther),
            icon = LucideR.drawable.lucide_ic_arrow_right,
            direction = FocusDriveDirection.FAR,
            enabled = !state.isBusy(CameraOperation.FOCUS),
            actions = actions,
        )
    }
}

@Composable
private fun FocusDriveDirectionRow(
    label: String,
    @androidx.annotation.DrawableRes icon: Int,
    direction: FocusDriveDirection,
    enabled: Boolean,
    actions: CameraActions,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, Modifier.width(72.dp), color = AppSubtleText, maxLines = 1)
        FocusDriveStep.entries.forEachIndexed { index, step ->
            val description = stringResource(R.string.focus_drive_step, label, index + 1)
            Button(
                onClick = { actions.driveFocus(direction, step) },
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = AppSurfaceHigh, contentColor = AppText),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("focus-drive-${direction.name}-${step.name}")
                    .semantics { contentDescription = description },
            ) {
                Icon(painterResource(icon), null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text((index + 1).toString())
            }
        }
    }
}

@Composable
private fun AdvancedSettingRow(setting: CameraSettingControl, actions: CameraActions) {
    if (setting.key.lowercase() in RANGE_SETTING_KEYS) {
        RangeSettingRow(setting, actions)
        return
    }
    val selectedIndex = setting.values.indexOf(setting.value).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    LaunchedEffect(setting.key, setting.value, setting.values) {
        listState.scrollToItem(selectedIndex)
    }
    Column(
        modifier = Modifier.testTag("advanced-setting-${setting.key}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(cameraSettingLabel(setting), color = AppText, fontWeight = FontWeight.SemiBold)
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.testTag("advanced-setting-values-${setting.key}"),
        ) {
            items(setting.values) { value ->
                Box(
                    Modifier
                        .testTag("advanced-setting-value-${setting.key}-$value")
                        .height(48.dp)
                        .background(if (value == setting.value) AppAccent else AppSurfaceHigh, RoundedCornerShape(6.dp))
                        .clickable { actions.setCameraSetting(setting.key, value) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        localizedCameraValue(setting.key, value),
                        color = if (value == setting.value) AppBackground else AppText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun RangeSettingRow(setting: CameraSettingControl, actions: CameraActions) {
    val currentIndex = setting.values.indexOf(setting.value).coerceAtLeast(0)
    val settingLabel = cameraSettingLabel(setting)
    var pendingIndex by remember(setting.key) { mutableFloatStateOf(currentIndex.toFloat()) }
    LaunchedEffect(setting.value, setting.values) {
        pendingIndex = currentIndex.toFloat()
    }
    val selectedIndex = pendingIndex.roundToInt().coerceIn(setting.values.indices)
    val selectedValue = setting.values[selectedIndex]
    val selectedValueLabel = if (setting.key.equals("zoom", ignoreCase = true)) {
        stringResource(R.string.zoom_value, selectedValue)
    } else {
        selectedValue
    }
    Column(
        modifier = Modifier.testTag("advanced-setting-${setting.key}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                settingLabel,
                color = AppText,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(selectedValueLabel, color = AppAccent, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = pendingIndex,
            onValueChange = { pendingIndex = it },
            onValueChangeFinished = {
                if (selectedValue != setting.value) actions.setCameraSetting(setting.key, selectedValue)
            },
            valueRange = 0f..setting.values.lastIndex.toFloat(),
            steps = if (setting.values.size <= 101) {
                (setting.values.size - 2).coerceAtLeast(0)
            } else {
                0
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("advanced-setting-values-${setting.key}")
                .semantics { contentDescription = "$settingLabel $selectedValueLabel" },
        )
    }
}

@Composable
private fun cameraSettingLabel(setting: CameraSettingControl): String = when (setting.key.lowercase()) {
    "afmethod" -> stringResource(R.string.setting_af_method)
    "afoperation" -> stringResource(R.string.setting_af_operation)
    "continuousaf" -> stringResource(R.string.setting_continuous_af)
    "drivemode" -> stringResource(R.string.setting_drive_mode)
    "meteringmode" -> stringResource(R.string.setting_metering_mode)
    "flashmode" -> stringResource(R.string.setting_flash_mode)
    "picturestyle" -> stringResource(R.string.setting_picture_style)
    "moviemode" -> stringResource(R.string.setting_movie_mode)
    "shootingmode", "autoexposuremode" -> stringResource(R.string.setting_shooting_mode)
    "stillimagequality" -> stringResource(R.string.setting_image_quality)
    "stillimagequality.raw" -> stringResource(R.string.setting_image_quality_raw)
    "stillimagequality.jpeg" -> stringResource(R.string.setting_image_quality_jpeg)
    "stillimagequality.heif" -> stringResource(R.string.setting_image_quality_heif)
    "stillimagequalitysd" -> stringResource(R.string.setting_image_quality_sd)
    "stillimagequalitycf" -> stringResource(R.string.setting_image_quality_cf)
    "moviequality" -> stringResource(R.string.setting_movie_quality)
    "highframerate" -> stringResource(R.string.setting_high_frame_rate)
    "moviecropping" -> stringResource(R.string.setting_movie_cropping)
    "movieformat" -> stringResource(R.string.setting_movie_format)
    "movieservoaf" -> stringResource(R.string.setting_movie_servo_af)
    "colortemperature" -> stringResource(R.string.setting_color_temperature)
    "exposurecompensation" -> stringResource(R.string.setting_exposure_compensation)
    "whitebalanceadjusta" -> stringResource(R.string.setting_white_balance_shift_a)
    "whitebalanceadjustb" -> stringResource(R.string.setting_white_balance_shift_b)
    "wbshift.ba" -> stringResource(R.string.setting_white_balance_shift_ba)
    "wbshift.mg" -> stringResource(R.string.setting_white_balance_shift_mg)
    "colorspace" -> stringResource(R.string.setting_color_space)
    "aspectratio" -> stringResource(R.string.setting_aspect_ratio)
    "zoom" -> stringResource(R.string.setting_zoom)
    "soundrecording" -> stringResource(R.string.setting_sound_recording)
    "soundrecordinglevel" -> stringResource(R.string.setting_sound_recording_level)
    "windfilter" -> stringResource(R.string.setting_wind_filter)
    "attenuator" -> stringResource(R.string.setting_attenuator)
    "focusbracketing" -> stringResource(R.string.setting_focus_bracketing)
    "focusbracketingnumberofshots" -> stringResource(R.string.setting_focus_bracketing_shots)
    "focusbracketingfocusincrement" -> stringResource(R.string.setting_focus_bracketing_increment)
    "focusbracketingexposuresmoothing" -> stringResource(R.string.setting_focus_bracketing_exposure_smoothing)
    "zoomspeed" -> stringResource(R.string.setting_power_zoom_speed)
    "autopoweroff" -> stringResource(R.string.setting_auto_power_off)
    "beep" -> stringResource(R.string.setting_beep)
    "displayoff" -> stringResource(R.string.setting_display_off)
    "capturetarget" -> stringResource(R.string.setting_capture_target)
    "capturestorage" -> stringResource(R.string.setting_capture_storage)
    "cardselectionstillimage" -> stringResource(R.string.setting_still_image_card)
    "cardselectionmovie" -> stringResource(R.string.setting_movie_card)
    "directoryselection" -> stringResource(R.string.setting_capture_directory)
    "highisonr" -> stringResource(R.string.setting_high_iso_noise_reduction)
    "alomode" -> stringResource(R.string.setting_auto_lighting_optimizer)
    "aeb" -> stringResource(R.string.setting_aeb)
    "ae" -> stringResource(R.string.setting_ae_mode)
    else -> setting.label
}

private val RANGE_SETTING_KEYS = setOf(
    "zoom",
    "soundrecordinglevel",
    "focusbracketingnumberofshots",
    "focusbracketingfocusincrement",
)
