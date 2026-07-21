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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.openeos.control.R
import com.composables.icons.lucide.R as LucideR
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraSettingControl
import dev.openeos.control.data.LiveViewSize
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CameraControlScreen(state: CameraUiState, actions: CameraActions) {
    BoxWithConstraints(
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
        if (maxWidth > maxHeight) LandscapeControls(state, actions) else PortraitControls(state, actions)
    }
    SettingSheets(state, actions)
}

@Composable
private fun PortraitControls(state: CameraUiState, actions: CameraActions) {
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
private fun LandscapeControls(state: CameraUiState, actions: CameraActions) {
    Box(Modifier.fillMaxSize()) {
        LiveViewFrame(
            state,
            actions,
            if (state.hudVisible) {
                Modifier.align(Alignment.CenterStart).fillMaxWidth(0.66f).fillMaxHeight()
            } else {
                Modifier.fillMaxSize()
            },
        )
        if (state.hudVisible) {
            CameraOverlayHeader(state, actions, Modifier.align(Alignment.TopStart).fillMaxWidth(0.66f))
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxWidth(0.34f)
                    .fillMaxHeight()
                    .background(Color(0xE6101214))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LandscapeExposureGrid(state, actions)
                Spacer(Modifier.height(4.dp))
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
    val values: List<String>
    val current: String?
    val onSelect: (String) -> Unit
    when (picker) {
        SettingPicker.ISO -> {
            title = stringResource(R.string.iso)
            values = state.capabilities?.iso.orEmpty()
            current = state.status?.exposure?.iso
            onSelect = actions.setIso
        }
        SettingPicker.SHUTTER -> {
            title = stringResource(R.string.shutter)
            values = state.capabilities?.shutter.orEmpty()
            current = state.status?.exposure?.shutter
            onSelect = actions.setShutter
        }
        SettingPicker.APERTURE -> {
            title = stringResource(R.string.aperture)
            values = state.capabilities?.aperture.orEmpty()
            current = state.status?.exposure?.aperture
            onSelect = actions.setAperture
        }
        SettingPicker.WHITE_BALANCE -> {
            title = stringResource(R.string.white_balance)
            values = state.capabilities?.whiteBalance.orEmpty()
            current = state.status?.exposure?.whiteBalance
            onSelect = actions.setWhiteBalance
        }
        else -> return
    }
    ModalBottomSheet(onDismissRequest = actions.closePicker, containerColor = AppSurface) {
        ExposurePickerTabs(state, picker, actions)
        key(picker) {
            ExposureDial(title, values, current, state.isBusy(CameraOperation.SETTING), onSelect)
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
    values: List<String>,
    current: String?,
    isApplying: Boolean,
    onSelect: (String) -> Unit,
) {
    if (values.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
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

    Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = AppMutedText)
                Text(values[selectedIndex], color = AppText, fontWeight = FontWeight.Bold)
            }
            if (isApplying) CircularProgressIndicator(Modifier.size(24.dp), color = AppAccent, strokeWidth = 2.dp)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(104.dp)) {
            val itemWidth = 88.dp
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
                            values[index],
                            color = if (selected) AppText else AppMutedText,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
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
    ModalBottomSheet(onDismissRequest = actions.closePicker, containerColor = AppSurface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.live_view_settings), color = AppText, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.auto_refresh), color = AppText, modifier = Modifier.weight(1f))
                Switch(state.liveViewAutoRefresh, actions.setAutoRefresh)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.composition_grid), color = AppText, modifier = Modifier.weight(1f))
                Switch(state.showGrid, actions.setGridVisible)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.live_view_frame_rate), color = AppText, fontWeight = FontWeight.SemiBold)
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

@Composable
private fun liveViewSizeLabel(size: LiveViewSize): String = stringResource(
    when (size) {
        LiveViewSize.SMALL -> R.string.size_small
        LiveViewSize.MEDIUM -> R.string.size_medium
        LiveViewSize.LARGE -> R.string.size_large
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreSettingsSheet(state: CameraUiState, actions: CameraActions) {
    val settings = settingsForMode(state.capabilities?.advancedSettings.orEmpty(), state.captureMode)
    ModalBottomSheet(onDismissRequest = actions.closePicker, containerColor = AppSurface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.more_settings), color = AppText, fontWeight = FontWeight.Bold)
            if (state.supports(CameraFeature.SHUTTER_HALF_PRESS)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.focus_with_shutter), color = AppText, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.focus_with_shutter_hint), color = AppSubtleText)
                    }
                    Button(
                        onClick = actions.focusWithShutter,
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
            if (settings.isEmpty()) Text(stringResource(R.string.no_settings), color = AppSubtleText)
            settings.forEach { setting -> AdvancedSettingRow(setting, actions) }
        }
    }
}

@Composable
private fun AdvancedSettingRow(setting: CameraSettingControl, actions: CameraActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(cameraSettingLabel(setting), color = AppText, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(setting.values) { value ->
                Box(
                    Modifier.height(48.dp).background(if (value == setting.value) AppAccent else AppSurfaceHigh, RoundedCornerShape(6.dp)).clickable { actions.setCameraSetting(setting.key, value) }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(value, color = if (value == setting.value) AppBackground else AppText, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun cameraSettingLabel(setting: CameraSettingControl): String = when (setting.key.lowercase()) {
    "afmethod" -> stringResource(R.string.setting_af_method)
    "afoperation" -> stringResource(R.string.setting_af_operation)
    "drivemode" -> stringResource(R.string.setting_drive_mode)
    "meteringmode" -> stringResource(R.string.setting_metering_mode)
    "picturestyle" -> stringResource(R.string.setting_picture_style)
    "shootingmode" -> stringResource(R.string.setting_shooting_mode)
    "stillimagequality" -> stringResource(R.string.setting_image_quality)
    "moviequality" -> stringResource(R.string.setting_movie_quality)
    "colortemperature" -> stringResource(R.string.setting_color_temperature)
    "exposurecompensation" -> stringResource(R.string.setting_exposure_compensation)
    "ae" -> stringResource(R.string.setting_ae_mode)
    else -> setting.label
}
