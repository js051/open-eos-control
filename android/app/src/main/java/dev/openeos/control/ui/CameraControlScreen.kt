package dev.openeos.control.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
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
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (maxWidth > maxHeight) LandscapeControls(state, actions) else PortraitControls(state, actions)
    }
    SettingSheets(state, actions)
}

@Composable
private fun PortraitControls(state: CameraUiState, actions: CameraActions) {
    Box(Modifier.fillMaxSize()) {
        LiveViewFrame(state, actions, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopCenter)) {
            CameraOverlayHeader(state, actions)
            CaptureModeSegment(
                state,
                actions,
                Modifier.align(Alignment.CenterHorizontally).widthIn(max = 220.dp).padding(top = 6.dp),
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xE6101214)),
        ) {
            ExposureStrip(state, actions)
            ModeQuickSettings(state, actions)
            CaptureBar(state, actions)
        }
    }
}

@Composable
private fun LandscapeControls(state: CameraUiState, actions: CameraActions) {
    Box(Modifier.fillMaxSize()) {
        LiveViewFrame(
            state,
            actions,
            Modifier.align(Alignment.CenterStart).fillMaxWidth(0.66f).fillMaxHeight(),
        )
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
            CaptureModeSegment(state, actions)
            LandscapeExposureGrid(state, actions)
            Spacer(Modifier.height(4.dp))
            CaptureBar(state, actions)
        }
    }
}

@Composable
private fun CaptureModeSegment(state: CameraUiState, actions: CameraActions, modifier: Modifier = Modifier) {
    ModeSegment(
        firstLabel = stringResource(R.string.photo),
        secondLabel = stringResource(R.string.video),
        firstSelected = state.captureMode == CaptureMode.PHOTO,
        onFirst = { actions.setCaptureMode(CaptureMode.PHOTO) },
        onSecond = { actions.setCaptureMode(CaptureMode.VIDEO) },
        modifier = modifier.fillMaxWidth(),
    )
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
            ToolIconButton(LucideR.drawable.lucide_ic_video, stringResource(R.string.live_view_settings), { actions.openPicker(SettingPicker.LIVE_VIEW) }, tint = AppAccent)
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

@Composable
private fun ModeQuickSettings(state: CameraUiState, actions: CameraActions) {
    val settings = settingsForMode(state.capabilities?.advancedSettings.orEmpty(), state.captureMode)
        .filter { setting ->
            val key = setting.key.lowercase()
            if (state.captureMode == CaptureMode.VIDEO) {
                listOf("movie", "video", "frame", "codec", "record").any(key::contains)
            } else {
                listOf("af", "drive", "quality").any(key::contains)
            }
        }
        .take(3)
    if (settings.isEmpty()) return
    LazyRow(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(settings, key = { it.key }) { setting ->
            Column(
                Modifier.width(120.dp).fillMaxHeight().background(AppSurface, RoundedCornerShape(6.dp)).clickable { actions.openPicker(SettingPicker.MORE) }.padding(6.dp),
            ) {
                Text(setting.label, color = AppMutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(setting.value, color = AppText, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingSheets(state: CameraUiState, actions: CameraActions) {
    when (state.activeSettingPicker) {
        SettingPicker.ISO -> ValueSheet(stringResource(R.string.iso), state.capabilities?.iso.orEmpty(), state.status?.exposure?.iso, state.isBusy(CameraOperation.SETTING), actions.setIso, actions.closePicker)
        SettingPicker.SHUTTER -> ValueSheet(stringResource(R.string.shutter), state.capabilities?.shutter.orEmpty(), state.status?.exposure?.shutter, state.isBusy(CameraOperation.SETTING), actions.setShutter, actions.closePicker)
        SettingPicker.APERTURE -> ValueSheet(stringResource(R.string.aperture), state.capabilities?.aperture.orEmpty(), state.status?.exposure?.aperture, state.isBusy(CameraOperation.SETTING), actions.setAperture, actions.closePicker)
        SettingPicker.WHITE_BALANCE -> ValueSheet(stringResource(R.string.white_balance), state.capabilities?.whiteBalance.orEmpty(), state.status?.exposure?.whiteBalance, state.isBusy(CameraOperation.SETTING), actions.setWhiteBalance, actions.closePicker)
        SettingPicker.LIVE_VIEW -> LiveViewSettingsSheet(state, actions)
        SettingPicker.MORE -> MoreSettingsSheet(state, actions)
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ValueSheet(
    title: String,
    values: List<String>,
    current: String?,
    isApplying: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppSurface) {
        ExposureDial(title, values, current, isApplying, onSelect, onDismiss)
    }
}

@Composable
private fun ExposureDial(
    title: String,
    values: List<String>,
    current: String?,
    isApplying: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
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
            Modifier.fillMaxWidth().height(52.dp).padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = AppMutedText)
                Text(values[selectedIndex], color = AppText, fontWeight = FontWeight.Bold)
            }
            if (isApplying) CircularProgressIndicator(Modifier.size(24.dp), color = AppAccent, strokeWidth = 2.dp)
            ToolIconButton(LucideR.drawable.lucide_ic_x, stringResource(R.string.dismiss), onDismiss)
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
    ModalBottomSheet(onDismissRequest = actions.closePicker, containerColor = AppSurface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.live_view_settings), color = AppText, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.auto_refresh), color = AppText, modifier = Modifier.weight(1f))
                Switch(state.liveViewAutoRefresh, actions.setAutoRefresh)
            }
            Text(stringResource(R.string.fps_value, state.liveViewFrameRateFps), color = AppText)
            Slider(
                value = state.liveViewFrameRateFps.toFloat(),
                onValueChange = { actions.setFps(it.roundToInt()) },
                valueRange = minFps.toFloat()..maxFps.toFloat(),
                steps = (maxFps - minFps - 1).coerceAtLeast(0),
            )
            Text(stringResource(R.string.size), color = AppText)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.capabilities?.liveView?.sizes.orEmpty()) { size ->
                    Box(
                        Modifier.height(48.dp).background(if (size == state.liveViewSize) AppAccent else AppSurfaceHigh, RoundedCornerShape(6.dp)).clickable { actions.setLiveViewSize(size) }.padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(size.label, color = if (size == state.liveViewSize) AppBackground else AppText) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreSettingsSheet(state: CameraUiState, actions: CameraActions) {
    val settings = settingsForMode(state.capabilities?.advancedSettings.orEmpty(), state.captureMode)
    ModalBottomSheet(onDismissRequest = actions.closePicker, containerColor = AppSurface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.more_settings), color = AppText, fontWeight = FontWeight.Bold)
            if (settings.isEmpty()) Text(stringResource(R.string.no_settings), color = AppSubtleText)
            settings.forEach { setting -> AdvancedSettingRow(setting, actions) }
        }
    }
}

@Composable
private fun AdvancedSettingRow(setting: CameraSettingControl, actions: CameraActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(setting.label, color = AppText, fontWeight = FontWeight.SemiBold)
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
