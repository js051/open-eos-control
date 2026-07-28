package dev.openeos.control.ui

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.unit.dp
import dev.openeos.control.R
import com.composables.icons.lucide.R as LucideR
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraSettingControl
import dev.openeos.control.data.FocusDriveDirection
import dev.openeos.control.data.FocusDriveStep
import dev.openeos.control.data.LiveViewSize
import dev.openeos.control.data.LiveViewSource
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CameraControlScreen(state: CameraUiState, actions: CameraActions) {
    Box(
        Modifier
            .fillMaxSize()
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
private fun CaptureBar(state: CameraUiState, actions: CameraActions) {
    val feature = if (state.captureMode == CaptureMode.PHOTO) CameraFeature.STILL_CAPTURE else CameraFeature.VIDEO_RECORDING
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.fillMaxWidth().height(92.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ToolIconButton(LucideR.drawable.lucide_ic_settings, stringResource(R.string.more_settings), { actions.openPicker(SettingPicker.MORE) })
            CaptureButton(state, actions)
            LiveViewFpsButton(state, { actions.openPicker(SettingPicker.LIVE_VIEW) })
        }
        if (!state.supports(feature)) {
            Text(
                stringResource(if (state.captureMode == CaptureMode.PHOTO) R.string.capture_not_supported else R.string.recording_not_supported),
                color = AppWarning,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingSheets(state: CameraUiState, actions: CameraActions) {
    when (state.activeSettingPicker) {
        SettingPicker.ISO,
        SettingPicker.SHUTTER,
        SettingPicker.APERTURE,
        SettingPicker.WHITE_BALANCE,
        -> ExposureSettingsSheet(state, actions)
        SettingPicker.LIVE_VIEW -> LiveViewSettingsSheet(state, actions)
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
                state = listState,
                contentPadding = PaddingValues(horizontal = edgePadding),
                flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
            ) {
                items(values.size, key = { values[it] }) { index ->
                    val selected = index == selectedIndex
                    Box(
                        Modifier
                            .width(itemWidth)
                            .height(88.dp)
                            .clickable(enabled = !isApplying) {
                                selectedIndex = index
                                onSelect(values[index])
                                scope.launch { listState.animateScrollToItem(index) }
                            },
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
    CameraSettingsSurface(
        onDismissRequest = actions.closePicker,
        skipPartiallyExpanded = true,
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
                            modifier = Modifier.height(48.dp),
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
                if (
                    settings.isEmpty() &&
                    !state.supports(CameraFeature.CLICK_WHITE_BALANCE) &&
                    !state.supports(CameraFeature.AUTOFOCUS) &&
                    !state.supports(CameraFeature.SHUTTER_HALF_PRESS) &&
                    !state.supports(CameraFeature.FOCUS_DRIVE)
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
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_focus), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.tap_action_focus))
                }
            }
            Button(
                onClick = { actions.setLiveViewTapAction(LiveViewTapAction.WHITE_BALANCE) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedAction == LiveViewTapAction.WHITE_BALANCE) AppWarning else AppSurfaceHigh,
                    contentColor = if (selectedAction == LiveViewTapAction.WHITE_BALANCE) AppBackground else AppText,
                ),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(painterResource(LucideR.drawable.lucide_ic_pipette), null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tap_action_white_balance))
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
                    Modifier.height(48.dp).background(if (value == setting.value) AppAccent else AppSurfaceHigh, RoundedCornerShape(6.dp)).clickable { actions.setCameraSetting(setting.key, value) }.padding(horizontal = 14.dp),
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
private fun cameraSettingLabel(setting: CameraSettingControl): String = when (setting.key.lowercase()) {
    "afmethod" -> stringResource(R.string.setting_af_method)
    "afoperation" -> stringResource(R.string.setting_af_operation)
    "continuousaf" -> stringResource(R.string.setting_continuous_af)
    "drivemode" -> stringResource(R.string.setting_drive_mode)
    "meteringmode" -> stringResource(R.string.setting_metering_mode)
    "flashmode" -> stringResource(R.string.setting_flash_mode)
    "picturestyle" -> stringResource(R.string.setting_picture_style)
    "shootingmode", "autoexposuremode" -> stringResource(R.string.setting_shooting_mode)
    "stillimagequality" -> stringResource(R.string.setting_image_quality)
    "stillimagequality.raw" -> stringResource(R.string.setting_image_quality_raw)
    "stillimagequality.jpeg" -> stringResource(R.string.setting_image_quality_jpeg)
    "stillimagequality.heif" -> stringResource(R.string.setting_image_quality_heif)
    "stillimagequalitysd" -> stringResource(R.string.setting_image_quality_sd)
    "stillimagequalitycf" -> stringResource(R.string.setting_image_quality_cf)
    "moviequality" -> stringResource(R.string.setting_movie_quality)
    "movieservoaf" -> stringResource(R.string.setting_movie_servo_af)
    "colortemperature" -> stringResource(R.string.setting_color_temperature)
    "exposurecompensation" -> stringResource(R.string.setting_exposure_compensation)
    "whitebalanceadjusta" -> stringResource(R.string.setting_white_balance_shift_a)
    "whitebalanceadjustb" -> stringResource(R.string.setting_white_balance_shift_b)
    "wbshift.ba" -> stringResource(R.string.setting_white_balance_shift_ba)
    "wbshift.mg" -> stringResource(R.string.setting_white_balance_shift_mg)
    "colorspace" -> stringResource(R.string.setting_color_space)
    "aspectratio" -> stringResource(R.string.setting_aspect_ratio)
    "zoomspeed" -> stringResource(R.string.setting_power_zoom_speed)
    "autopoweroff" -> stringResource(R.string.setting_auto_power_off)
    "capturetarget" -> stringResource(R.string.setting_capture_target)
    "highisonr" -> stringResource(R.string.setting_high_iso_noise_reduction)
    "aeb" -> stringResource(R.string.setting_aeb)
    "ae" -> stringResource(R.string.setting_ae_mode)
    else -> setting.label
}
