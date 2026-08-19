package dev.openeos.control.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.text.format.Formatter
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.openeos.control.R
import com.composables.icons.lucide.R as LucideR
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraStatus
import dev.openeos.control.data.LiveViewMagnification
import dev.openeos.control.data.NativeLiveViewSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolIconButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = AppText,
    testTag: String? = null,
) {
    Box(modifier) {
        TooltipBox(
            positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(description) } },
            state = rememberTooltipState(),
        ) {
            IconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier
                    .size(48.dp)
                    .then(if (testTag == null) Modifier else Modifier.testTag(testTag)),
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
        CameraRotatingSlot(
            modifier
                .size(64.dp)
                .testTag("fps-control")
                .clickable(onClick = onClick)
                .semantics { contentDescription = description; role = Role.Button },
        ) {
            Column(
                Modifier.testTag("fps-content"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painterResource(LucideR.drawable.lucide_ic_gauge),
                    null,
                    Modifier.size(24.dp),
                    tint = AppAccent,
                )
                Text(
                    stringResource(R.string.fps_compact, state.liveViewFrameRateFps),
                    color = AppText,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureReviewButton(
    state: CameraUiState,
    actions: CameraActions,
    modifier: Modifier = Modifier,
) {
    if (!state.supports(CameraFeature.MEDIA_BROWSER)) {
        Box(modifier.size(64.dp))
        return
    }
    val item = state.captureReviewItem
    val enabled = item != null
    val description = item?.let { stringResource(R.string.open_latest_media_named, it.name) }
        ?: stringResource(R.string.open_latest_media)
    TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState(),
    ) {
        CameraRotatingSlot(
            modifier = modifier
                .size(64.dp)
                .testTag("capture-review-button")
                .clickable(enabled = enabled, onClick = actions.openCaptureReview)
                .semantics {
                    contentDescription = description
                    role = Role.Button
                },
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(6.dp),
                color = if (enabled) AppSurface else AppSurface.copy(alpha = 0.72f),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppBorder),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val thumbnail = state.captureReviewThumbnail
                    if (thumbnail != null) {
                        Image(
                            bitmap = thumbnail.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(
                            painterResource(LucideR.drawable.lucide_ic_images),
                            contentDescription = null,
                            tint = AppAccent,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    if (state.captureReviewLoading) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)))
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = AppText,
                        )
                    }
                }
            }
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
    val storage = cameraStorageLabel(state.status, state.captureMode)
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

internal val CAMERA_OVERLAY_HEADER_HEIGHT = 64.dp

@Composable
fun CameraOverlayHeader(state: CameraUiState, actions: CameraActions, modifier: Modifier = Modifier) {
    val batteryDescription = state.status?.batteryLevel?.let { stringResource(R.string.battery_percent, it) }
        ?: stringResource(R.string.unknown)
    val batteryValue = state.status?.batteryLevel?.let { "$it%" } ?: "-"
    val fullCameraName = state.info?.model ?: stringResource(R.string.unknown)
    val cameraHudName = fullCameraName.toCameraHudName()
    val fullStorage = cameraStorageLabel(state.status, state.captureMode)
    val storageValue = cameraStorageHudValue(state.status, state.captureMode)
    var menuExpanded by remember { mutableStateOf(false) }
    var statusExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CAMERA_OVERLAY_HEADER_HEIGHT)
            .testTag("camera-overlay-header")
            .background(Color(0xB8000000))
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(8.dp).background(AppSuccess, CircleShape))
        CameraModelIndicator(
            value = cameraHudName,
            description = fullCameraName,
            onClick = { statusExpanded = true },
            modifier = Modifier.width(76.dp).height(CAMERA_OVERLAY_HEADER_HEIGHT),
        )
        Box(Modifier.weight(1f))
        CameraStatusIndicator(
            icon = batteryStatusIcon(state.status?.batteryLevel),
            value = batteryValue,
            description = batteryDescription,
            testTag = "battery-status",
            onClick = { statusExpanded = true },
            modifier = Modifier.width(48.dp).height(CAMERA_OVERLAY_HEADER_HEIGHT),
        )
        CameraStatusIndicator(
            icon = LucideR.drawable.lucide_ic_memory_stick,
            value = storageValue,
            description = fullStorage,
            testTag = "storage-status",
            onClick = { statusExpanded = true },
            modifier = Modifier.width(58.dp).height(CAMERA_OVERLAY_HEADER_HEIGHT),
        )
        ToolIconButton(
            LucideR.drawable.lucide_ic_eye_off,
            stringResource(R.string.hide_hud),
            { actions.setHudVisible(false) },
        )
        ToolIconButton(
            LucideR.drawable.lucide_ic_ellipsis_vertical,
            stringResource(R.string.more_actions),
            { menuExpanded = true },
            testTag = "camera-action-menu-button",
        )
    }
    if (menuExpanded) {
        CameraActionMenuDialog(
            state = state,
            actions = actions,
            onDismissRequest = { menuExpanded = false },
        )
    }
    if (statusExpanded) {
        CameraStatusDetailsDialog(
            cameraName = fullCameraName,
            battery = batteryDescription,
            storage = fullStorage,
            onDismissRequest = { statusExpanded = false },
        )
    }
}

@Composable
private fun CameraActionMenuDialog(
    state: CameraUiState,
    actions: CameraActions,
    onDismissRequest: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        DarkSheetSystemBarsEffect()
        Box(
            Modifier
                .fillMaxSize()
                .testTag("camera-action-menu"),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.58f))
                    .clickable(onClick = onDismissRequest)
                    .testTag("camera-action-menu-scrim"),
            )
            CameraActionMenuPanel(state, actions, onDismissRequest)
        }
    }
}

@Composable
internal fun CameraActionMenuPanel(
    state: CameraUiState,
    actions: CameraActions,
    onDismissRequest: () -> Unit,
) {
    val mediaAvailable = state.supports(CameraFeature.MEDIA_BROWSER)
    CameraReadableSlot(
        width = 328.dp,
        height = if (mediaAvailable) 352.dp else 296.dp,
        modifier = Modifier.testTag("camera-action-menu-rotation"),
        animateRotation = false,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("camera-action-menu-surface")
                .pointerInput(Unit) { detectTapGestures(onTap = {}) },
            shape = RoundedCornerShape(8.dp),
            color = AppSurface,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .testTag("camera-action-menu-content")
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.more_actions),
                        color = AppText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        modifier = Modifier.weight(1f),
                    )
                    ToolIconButton(
                        LucideR.drawable.lucide_ic_x,
                        stringResource(R.string.dismiss),
                        onDismissRequest,
                    )
                }
                if (mediaAvailable) {
                    CameraActionMenuItem(
                        icon = LucideR.drawable.lucide_ic_images,
                        label = stringResource(R.string.camera_media),
                        testTag = "camera-action-media",
                    ) {
                        onDismissRequest()
                        actions.setUiMode(UiMode.MEDIA)
                    }
                }
                CameraActionMenuItem(
                    icon = LucideR.drawable.lucide_ic_settings,
                    label = stringResource(R.string.more_settings),
                    testTag = "camera-action-settings",
                ) {
                    onDismissRequest()
                    actions.openPicker(SettingPicker.MORE)
                }
                CameraActionMenuItem(
                    icon = LucideR.drawable.lucide_ic_languages,
                    label = stringResource(R.string.language),
                    testTag = "camera-action-language",
                ) {
                    onDismissRequest()
                    actions.openPicker(SettingPicker.LANGUAGE)
                }
                CameraActionMenuItem(
                    icon = LucideR.drawable.lucide_ic_bug,
                    label = stringResource(R.string.debug),
                    testTag = "camera-action-debug",
                ) {
                    onDismissRequest()
                    actions.setUiMode(UiMode.DEBUG)
                }
                CameraActionMenuItem(
                    icon = LucideR.drawable.lucide_ic_unplug,
                    label = stringResource(R.string.disconnect),
                    testTag = "camera-action-disconnect",
                ) {
                    onDismissRequest()
                    actions.disconnect()
                }
            }
        }
    }
}

@Composable
private fun CameraActionMenuItem(
    @DrawableRes icon: Int,
    label: String,
    testTag: String,
    onClick: () -> Unit,
) {
    var fontSize by remember(label) { mutableStateOf(16.sp) }
    var hasVisualOverflow by remember(label) { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(onClick = onClick)
            .testTag(testTag)
            .semantics { contentDescription = label; role = Role.Button }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = AppSubtleText,
            modifier = Modifier.size(24.dp),
        )
        Text(
            label,
            color = AppText,
            fontSize = fontSize,
            lineHeight = fontSize * 1.15f,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .weight(1f)
                .testTag(
                    if (hasVisualOverflow) {
                        "$testTag-label-overflow"
                    } else {
                        "$testTag-label"
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

@Composable
private fun CameraModelIndicator(
    value: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CameraRotatingSquareSlot(
        size = 56.dp,
        modifier = modifier
            .testTag("camera-model-status")
            .clickable(onClick = onClick)
            .semantics { contentDescription = description; role = Role.Button },
    ) {
        CameraHudText(
            value = value,
            color = AppText,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxFontSize = 11.sp,
            minFontSize = 9.sp,
            maxLines = 2,
            softWrap = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
                .testTag("camera-name"),
        )
    }
}

@Composable
private fun CameraStatusIndicator(
    @DrawableRes icon: Int,
    value: String,
    description: String,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CameraRotatingSquareSlot(
        size = 52.dp,
        modifier = modifier
            .testTag(testTag)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description; role = Role.Button },
    ) {
        CameraStatusIndicatorContent(icon, value, testTag)
    }
}

@Composable
private fun CameraStatusIndicatorContent(
    @DrawableRes icon: Int,
    value: String,
    testTag: String,
) {
    Column(
        Modifier.testTag("$testTag-content"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = AppSubtleText,
            modifier = Modifier.size(14.dp),
        )
        CameraHudText(
            value = value,
            color = AppSubtleText,
            fontWeight = FontWeight.SemiBold,
            maxFontSize = 11.sp,
            minFontSize = 8.sp,
        )
    }
}

@Composable
private fun CameraHudText(
    value: String,
    color: Color,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    maxFontSize: TextUnit = 11.sp,
    minFontSize: TextUnit = 7.sp,
    maxLines: Int = 1,
    softWrap: Boolean = false,
) {
    val configuration = LocalConfiguration.current
    val rotationQuadrant = cameraRotationQuadrant(LocalCameraControlTargetRotation.current)
    var fontSize by remember(
        value,
        maxFontSize,
        minFontSize,
        configuration.fontScale,
        rotationQuadrant,
    ) { mutableStateOf(maxFontSize) }
    Text(
        text = value,
        color = color,
        fontSize = fontSize,
        lineHeight = fontSize * 1.15f,
        fontWeight = fontWeight,
        maxLines = maxLines,
        softWrap = softWrap,
        overflow = TextOverflow.Clip,
        textAlign = textAlign,
        modifier = modifier,
        onTextLayout = { result ->
            if ((result.didOverflowWidth || result.didOverflowHeight) && fontSize > minFontSize) {
                fontSize = (fontSize.value - 0.5f).coerceAtLeast(minFontSize.value).sp
            }
        },
    )
}

@Composable
private fun CameraStatusDetailsDialog(
    cameraName: String,
    battery: String,
    storage: String,
    onDismissRequest: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.58f))
                .clickable(onClick = onDismissRequest)
                .testTag("camera-status-dialog"),
            contentAlignment = Alignment.Center,
        ) {
            CameraReadableSlot(
                width = 336.dp,
                height = 288.dp,
                modifier = Modifier.testTag("camera-status-dialog-rotation"),
                animateRotation = false,
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures(onTap = {}) },
                    shape = RoundedCornerShape(8.dp),
                    color = AppSurface,
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.camera_status),
                                color = AppText,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = onDismissRequest,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    painterResource(LucideR.drawable.lucide_ic_x),
                                    stringResource(R.string.dismiss),
                                    tint = AppSubtleText,
                                )
                            }
                        }
                        CameraStatusDetail(
                            icon = LucideR.drawable.lucide_ic_camera,
                            label = stringResource(R.string.camera_profile),
                            value = cameraName,
                            testTag = "camera-status-model-detail",
                        )
                        CameraStatusDetail(
                            icon = LucideR.drawable.lucide_ic_battery_medium,
                            label = stringResource(R.string.battery),
                            value = battery,
                            testTag = "camera-status-battery-detail",
                        )
                        CameraStatusDetail(
                            icon = LucideR.drawable.lucide_ic_memory_stick,
                            label = stringResource(R.string.storage),
                            value = storage,
                            testTag = "camera-status-storage-detail",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraStatusDetail(
    @DrawableRes icon: Int,
    label: String,
    value: String,
    testTag: String,
) {
    Row(
        Modifier.fillMaxWidth().testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = AppAccent,
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(label, color = AppMutedText, fontSize = 12.sp)
            Text(
                value,
                color = AppText,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun CameraRotatingMessageDialog(
    title: String,
    message: String,
    onDismissRequest: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.58f))
                .clickable(onClick = onDismissRequest)
                .testTag("camera-message-dialog"),
            contentAlignment = Alignment.Center,
        ) {
            CameraReadableSlot(
                width = 336.dp,
                height = 240.dp,
                modifier = Modifier.testTag("camera-message-dialog-rotation"),
                animateRotation = false,
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures(onTap = {}) },
                    shape = RoundedCornerShape(8.dp),
                    color = AppSurface,
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painterResource(LucideR.drawable.lucide_ic_triangle_alert),
                                contentDescription = null,
                                tint = AppWarning,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                title,
                                color = AppText,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                modifier = Modifier.weight(1f).padding(start = 12.dp),
                            )
                            IconButton(onClick = onDismissRequest, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    painterResource(LucideR.drawable.lucide_ic_x),
                                    stringResource(R.string.dismiss),
                                    tint = AppSubtleText,
                                )
                            }
                        }
                        Text(
                            message,
                            color = AppSubtleText,
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            modifier = Modifier.testTag("camera-message-dialog-text"),
                        )
                    }
                }
            }
        }
    }
}

@DrawableRes
private fun batteryStatusIcon(level: Int?): Int = when {
    level == null -> LucideR.drawable.lucide_ic_battery_warning
    level <= 20 -> LucideR.drawable.lucide_ic_battery_low
    level >= 85 -> LucideR.drawable.lucide_ic_battery_full
    else -> LucideR.drawable.lucide_ic_battery_medium
}

@Composable
private fun cameraStorageLabel(status: CameraStatus?, captureMode: CaptureMode): String {
    val context = LocalContext.current
    return when {
        captureMode == CaptureMode.VIDEO && status?.remainingRecordingSeconds != null -> stringResource(
            R.string.recording_time_remaining,
            formatRecordingDuration(status.remainingRecordingSeconds),
        )
        captureMode == CaptureMode.PHOTO && status?.recordableShots != null -> pluralStringResource(
            R.plurals.storage_shots_remaining,
            status.recordableShots.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            status.recordableShots,
        )
        status?.storageFreeImages != null -> pluralStringResource(
            R.plurals.storage_shots_remaining,
            status.storageFreeImages.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            status.storageFreeImages,
        )
        status?.storageFreeBytes != null -> stringResource(
            R.string.storage_free_short,
            Formatter.formatShortFileSize(context, status.storageFreeBytes),
        )
        status?.mediaAvailable == true -> stringResource(R.string.storage_ready)
        else -> stringResource(R.string.storage_unknown)
    }
}

@Composable
private fun cameraStorageHudValue(status: CameraStatus?, captureMode: CaptureMode): String {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    return when {
        captureMode == CaptureMode.VIDEO && status?.remainingRecordingSeconds != null ->
            formatRecordingDuration(status.remainingRecordingSeconds)
        captureMode == CaptureMode.PHOTO && status?.recordableShots != null ->
            exactCameraCount(status.recordableShots, locale)
        status?.storageFreeImages != null -> exactCameraCount(status.storageFreeImages, locale)
        status?.storageFreeBytes != null -> Formatter.formatShortFileSize(context, status.storageFreeBytes)
        status?.mediaAvailable == true -> stringResource(R.string.storage_ready)
        else -> "-"
    }
}

internal fun String.toCameraHudName(): String {
    val compact = removePrefix("Canon ")
        .removePrefix("EOS ")
        .trim()
    return compact
        .replace(" Mark ", " ")
        .ifBlank { this }
}

internal fun exactCameraCount(value: Long, locale: Locale): String =
    NumberFormat.getIntegerInstance(locale).format(value.coerceAtLeast(0L))

internal fun formatRecordingDuration(value: Long): String {
    val seconds = value.coerceAtLeast(0L)
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    val remainder = seconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, remainder)
    } else {
        "%02d:%02d".format(Locale.ROOT, minutes, remainder)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveViewFrame(state: CameraUiState, actions: CameraActions, modifier: Modifier = Modifier) {
    val bitmap = state.liveViewBitmap
    var loadedFrameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val decodedFrame = bitmap ?: loadedFrameBitmap
    val sourceAspectRatio = decodedFrame?.takeIf { it.width > 0 && it.height > 0 }
        ?.let { it.width.toFloat() / it.height.toFloat() } ?: state.liveViewAspectRatio
    val displayAspectRatio = sourceAspectRatio * state.monitorSettings.desqueeze.horizontalScale
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
    val pixelAnalysisAvailable = !state.previewMode &&
        state.liveViewSource != dev.openeos.control.data.LiveViewSource.CCAPI_RTP &&
        state.nativeLiveViewSession == null
    var lutPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val lutRequestGeneration = remember { AtomicLong(0) }
    val lutRequests = remember { Channel<LiveViewLutRequest>(Channel.CONFLATED) }
    LaunchedEffect(decodedFrame, state.monitorSettings.cubeLut, pixelAnalysisAvailable) {
        val generation = lutRequestGeneration.incrementAndGet()
        lutRequests.trySend(
            LiveViewLutRequest(
                generation = generation,
                source = decodedFrame.takeIf { pixelAnalysisAvailable },
                lut = state.monitorSettings.cubeLut.takeIf { pixelAnalysisAvailable },
            )
        )
    }
    LaunchedEffect(lutRequests) {
        val processingJob = kotlin.coroutines.coroutineContext[Job]
        for (request in lutRequests) {
            val source = request.source
            val lut = request.lut
            if (source == null || lut == null) {
                if (request.generation == lutRequestGeneration.get()) {
                    val previous = lutPreviewBitmap
                    lutPreviewBitmap = null
                    previous?.recycle()
                }
                continue
            }
            val transformed = withContext(Dispatchers.Default) {
                applyCubeLut(source, lut) { processingJob?.ensureActive() }
            }
            if (request.generation == lutRequestGeneration.get()) {
                val previous = lutPreviewBitmap
                lutPreviewBitmap = transformed
                previous?.takeUnless { it === transformed }?.recycle()
            } else {
                transformed.recycle()
            }
        }
    }
    DisposableEffect(lutRequests) {
        onDispose {
            lutRequests.close()
            lutPreviewBitmap?.recycle()
            lutPreviewBitmap = null
        }
    }
    val lutPreviewActive = pixelAnalysisAvailable && state.monitorSettings.cubeLut != null
    val analysisSource = if (pixelAnalysisAvailable) {
        if (lutPreviewActive) lutPreviewBitmap else decodedFrame
    } else {
        null
    }
    val monitorAnalysis by produceState<LiveViewMonitorAnalysis?>(
        initialValue = null,
        key1 = analysisSource,
        key2 = Pair(
            state.monitorSettings.histogramVisible,
            state.monitorSettings.waveformVisible,
        ),
        key3 = Triple(
            state.monitorSettings.zebraThresholdPercent,
            state.monitorSettings.falseColorEnabled,
            state.monitorSettings.focusPeakingEnabled,
        ),
    ) {
        value = if (analysisSource != null && state.monitorSettings.needsPixelAnalysis) {
            withContext(Dispatchers.Default) {
                runCatching {
                    analyzeLiveViewBitmap(
                        analysisSource,
                        state.monitorSettings.zebraThresholdPercent,
                        state.monitorSettings.focusPeakingEnabled,
                        state.monitorSettings.falseColorEnabled,
                        state.monitorSettings.waveformVisible,
                    )
                }.getOrNull()
            }
        } else {
            null
        }
    }
    val monitorOverlay = remember(monitorAnalysis) {
        monitorAnalysis?.overlayPixels?.let { pixels ->
            Bitmap.createBitmap(
                pixels,
                monitorAnalysis!!.width,
                monitorAnalysis!!.height,
                Bitmap.Config.ARGB_8888,
            )
        }
    }

    Box(
        modifier = modifier
            .testTag("live-view-frame")
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.previewMode -> BoxWithConstraints(
                modifier = Modifier.fillMaxSize().cameraPreviewViewport(state),
                contentAlignment = Alignment.Center,
            ) {
                val quarterTurn = cameraRotationSwapsDimensions(LocalCameraControlTargetRotation.current)
                val readableSize = offlinePreviewReadableSize(
                    availableWidth = maxWidth,
                    availableHeight = maxHeight,
                    quarterTurn = quarterTurn,
                )
                CameraReadableSlot(
                    width = readableSize.width,
                    height = readableSize.height,
                    modifier = Modifier.testTag("offline-preview-viewport"),
                    animateRotation = false,
                ) {
                    OfflinePreviewCopy(quarterTurn)
                }
            }
            state.nativeLiveViewSession != null -> NativeRtpLiveView(
                session = state.nativeLiveViewSession,
                modifier = Modifier.fitLiveViewContent(displayAspectRatio),
            )
            lutPreviewActive && lutPreviewBitmap != null -> Image(
                lutPreviewBitmap!!.asImageBitmap(),
                stringResource(R.string.live_view_lut_preview),
                Modifier
                    .fitLiveViewContent(displayAspectRatio)
                    .testTag("live-view-decoded-frame"),
                contentScale = ContentScale.FillBounds,
            )
            bitmap != null -> Image(
                bitmap.asImageBitmap(),
                stringResource(R.string.live_view),
                Modifier
                    .fitLiveViewContent(displayAspectRatio)
                    .testTag("live-view-decoded-frame"),
                contentScale = ContentScale.FillBounds,
            )
            state.liveViewFrameUrl != null -> AsyncImage(
                model = ImageRequest.Builder(context).data(state.liveViewFrameUrl).crossfade(false).build(),
                imageLoader = imageLoader,
                placeholder = lastFramePainter,
                contentDescription = stringResource(R.string.live_view),
                modifier = Modifier
                    .fitLiveViewContent(displayAspectRatio)
                    .testTag(
                        if (loadedFrameBitmap != null) {
                            "live-view-decoded-frame"
                        } else {
                            "live-view-loading-frame"
                        },
                    ),
                contentScale = ContentScale.FillBounds,
                onSuccess = { result ->
                    lastFramePainter = result.painter
                    loadedFrameBitmap = (result.result.drawable as? BitmapDrawable)?.bitmap
                },
            )
            else -> Box(
                modifier = Modifier.fillMaxSize().cameraPreviewViewport(state),
                contentAlignment = Alignment.Center,
            ) {
                CameraReadableSlot(
                    width = 288.dp,
                    height = 72.dp,
                    modifier = Modifier.testTag("live-view-unavailable-rotation"),
                    animateRotation = false,
                ) {
                    Text(
                        stringResource(R.string.live_view_unavailable),
                        color = AppMutedText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    )
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(canTap, tapAction, sourceAspectRatio) {
                    detectTapGestures { offset ->
                        if (canTap) {
                            mapLiveViewTap(
                                tapX = offset.x,
                                tapY = offset.y,
                                containerWidth = size.width.toFloat(),
                                containerHeight = size.height.toFloat(),
                                sourceAspectRatio = displayAspectRatio,
                            )?.let { point ->
                                when (tapAction) {
                                    LiveViewTapAction.FOCUS -> actions.tapFocus(point.x, point.y)
                                    LiveViewTapAction.WHITE_BALANCE -> actions.clickWhiteBalance(point.x, point.y)
                                    null -> Unit
                                }
                            }
                        }
                    }
                }
        )

        if (state.status?.recording == true) RecordingIndicator(Modifier.align(Alignment.CenterStart).padding(12.dp))
        if (state.bulbExposureActive) {
            BulbExposureIndicator(state.bulbStartedAtMillis, Modifier.align(Alignment.CenterStart).padding(12.dp))
        }
        if (state.supports(CameraFeature.CLICK_WHITE_BALANCE)) {
            val bottomPadding = liveViewOverlayBottomPadding(state)
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
                    .zIndex(1f)
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = bottomPadding)
                    .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(4.dp)),
            )
        }
        val targetMagnification = state.nextLiveViewMagnification()
        if (state.supports(CameraFeature.LIVE_VIEW_MAGNIFICATION) && targetMagnification != null) {
            val bottomPadding = liveViewOverlayBottomPadding(state)
            val target = targetMagnification
            val description = stringResource(R.string.live_view_magnify_to, target.value)
            val enabled = !state.isBusy(CameraOperation.LIVE_VIEW)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .zIndex(1f)
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = bottomPadding),
            ) {
                TooltipBox(
                    positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text(description) } },
                    state = rememberTooltipState(),
                ) {
                    CameraRotatingSlot(
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("live-view-magnification")
                            .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(4.dp))
                            .clickable(enabled = enabled) { actions.setLiveViewMagnification(target) }
                            .semantics {
                                contentDescription = description
                                role = Role.Button
                            },
                    ) {
                        Icon(
                            painterResource(
                                if (target != LiveViewMagnification.X1) {
                                    LucideR.drawable.lucide_ic_zoom_in
                                } else {
                                    LucideR.drawable.lucide_ic_zoom_out
                                }
                            ),
                            contentDescription = null,
                            tint = if (enabled) AppAccent else AppMutedText,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(
                            text = "${target.value}\u00d7",
                            color = if (enabled) AppText else AppMutedText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 3.dp, bottom = 2.dp),
                        )
                    }
                }
            }
        }
        monitorOverlay?.let { MonitoringPixelOverlay(it, displayAspectRatio) }
        MonitorGuidesOverlay(state.monitorSettings, displayAspectRatio)
        if (state.showGrid) GridOverlay(displayAspectRatio)
        if (state.monitorSettings.histogramVisible) {
            monitorAnalysis?.let { HistogramOverlay(it.histogram, state.hudVisible) }
        }
        if (state.monitorSettings.waveformVisible) {
            monitorAnalysis?.waveform?.let { WaveformOverlay(it, state.hudVisible) }
        }
        FocusIndicator(state.focusPoint, state.focusFeedback, displayAspectRatio)
        if (state.captureFeedback == CaptureFeedback.SUCCESS) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.72f)))
    }
}

private data class LiveViewLutRequest(
    val generation: Long,
    val source: Bitmap?,
    val lut: CubeLut?,
)

private fun Modifier.fitLiveViewContent(aspectRatio: Float): Modifier = layout { measurable, constraints ->
    val availableWidth = constraints.maxWidth.coerceAtLeast(1)
    val availableHeight = constraints.maxHeight.coerceAtLeast(1)
    val availableAspect = availableWidth.toFloat() / availableHeight
    val width: Int
    val height: Int
    if (availableAspect > aspectRatio) {
        height = availableHeight
        width = (height * aspectRatio).roundToInt().coerceIn(1, availableWidth)
    } else {
        width = availableWidth
        height = (width / aspectRatio).roundToInt().coerceIn(1, availableHeight)
    }
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(0, 0) }
}

@Composable
private fun MonitoringPixelOverlay(bitmap: Bitmap, displayAspectRatio: Float) {
    DisposableEffect(bitmap) {
        onDispose { bitmap.recycle() }
    }
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.fitLiveViewContent(displayAspectRatio),
        contentScale = ContentScale.FillBounds,
    )
}

@Composable
private fun BoxScope.HistogramOverlay(histogram: IntArray, hudVisible: Boolean) {
    val description = stringResource(R.string.histogram)
    Canvas(
        Modifier
            .align(Alignment.TopStart)
            .padding(start = 12.dp, top = if (hudVisible) 60.dp else 12.dp)
            .size(width = 156.dp, height = 82.dp)
            .background(Color.Black.copy(alpha = 0.64f), RoundedCornerShape(4.dp))
            .padding(8.dp)
            .semantics { contentDescription = description },
    ) {
        val peak = histogram.maxOrNull()?.coerceAtLeast(1) ?: 1
        val barWidth = size.width / histogram.size.coerceAtLeast(1)
        histogram.forEachIndexed { index, count ->
            val barHeight = size.height * count / peak.toFloat()
            drawRect(
                color = Color.White.copy(alpha = 0.86f),
                topLeft = Offset(index * barWidth, size.height - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth.coerceAtLeast(1f), barHeight),
            )
        }
    }
}

@Composable
private fun BoxScope.WaveformOverlay(waveform: LiveViewWaveform, hudVisible: Boolean) {
    val description = stringResource(R.string.luma_waveform)
    Canvas(
        Modifier
            .align(Alignment.TopStart)
            .padding(start = 12.dp, top = if (hudVisible) 60.dp else 12.dp)
            .size(width = 156.dp, height = 96.dp)
            .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(4.dp))
            .padding(8.dp)
            .semantics { contentDescription = description },
    ) {
        val columnWidth = size.width / waveform.width
        val rowHeight = size.height / waveform.height
        for (guide in 0..4) {
            val y = size.height * guide / 4f
            drawLine(
                color = Color.White.copy(alpha = 0.16f),
                start = Offset(0f, y.coerceAtMost(size.height - 1f)),
                end = Offset(size.width, y.coerceAtMost(size.height - 1f)),
                strokeWidth = 1f,
            )
        }
        val peak = waveform.density.maxOrNull()?.coerceAtLeast(1) ?: 1
        waveform.density.forEachIndexed { index, count ->
            if (count == 0) return@forEachIndexed
            val x = index % waveform.width
            val y = index / waveform.width
            val intensity = sqrt(count / peak.toFloat()).coerceIn(0.16f, 1f)
            drawRect(
                color = AppAccent.copy(alpha = intensity),
                topLeft = Offset(x * columnWidth, y * rowHeight),
                size = androidx.compose.ui.geometry.Size(
                    columnWidth.coerceAtLeast(1f),
                    rowHeight.coerceAtLeast(1f),
                ),
            )
        }
    }
}

@Composable
private fun MonitorGuidesOverlay(settings: LiveViewMonitorSettings, displayAspectRatio: Float) {
    if (settings.frameGuide == LiveViewFrameGuide.OFF && !settings.safeAreaVisible) return
    val description = stringResource(R.string.monitor_guides)
    Canvas(Modifier.fillMaxSize().semantics { contentDescription = description }) {
        val fitted = fittedLiveViewRect(size.width, size.height, displayAspectRatio)
        val content = androidx.compose.ui.geometry.Rect(
            left = fitted.left,
            top = fitted.top,
            right = fitted.left + fitted.width,
            bottom = fitted.top + fitted.height,
        )
        val frameAspect = settings.frameGuide.aspectRatio
        val frame = if (frameAspect == null) {
            content
        } else if (content.width / content.height > frameAspect) {
            val width = content.height * frameAspect
            androidx.compose.ui.geometry.Rect(
                left = content.left + (content.width - width) / 2f,
                top = content.top,
                right = content.left + (content.width + width) / 2f,
                bottom = content.bottom,
            )
        } else {
            val height = content.width / frameAspect
            androidx.compose.ui.geometry.Rect(
                left = content.left,
                top = content.top + (content.height - height) / 2f,
                right = content.right,
                bottom = content.top + (content.height + height) / 2f,
            )
        }
        if (frameAspect != null) {
            val shade = Color.Black.copy(alpha = 0.58f)
            drawRect(shade, Offset(content.left, content.top), androidx.compose.ui.geometry.Size(content.width, frame.top - content.top))
            drawRect(shade, Offset(content.left, frame.bottom), androidx.compose.ui.geometry.Size(content.width, content.bottom - frame.bottom))
            drawRect(shade, Offset(content.left, frame.top), androidx.compose.ui.geometry.Size(frame.left - content.left, frame.height))
            drawRect(shade, Offset(frame.right, frame.top), androidx.compose.ui.geometry.Size(content.right - frame.right, frame.height))
            drawRect(Color.White.copy(alpha = 0.74f), frame.topLeft, frame.size, style = Stroke(1.dp.toPx()))
        }
        if (settings.safeAreaVisible) {
            fun safeRect(scale: Float): androidx.compose.ui.geometry.Rect {
                val width = frame.width * scale
                val height = frame.height * scale
                return androidx.compose.ui.geometry.Rect(
                    left = frame.center.x - width / 2f,
                    top = frame.center.y - height / 2f,
                    right = frame.center.x + width / 2f,
                    bottom = frame.center.y + height / 2f,
                )
            }
            val actionSafe = safeRect(0.9f)
            val titleSafe = safeRect(0.8f)
            drawRect(Color.White.copy(alpha = 0.58f), actionSafe.topLeft, actionSafe.size, style = Stroke(1.dp.toPx()))
            drawRect(Color.White.copy(alpha = 0.38f), titleSafe.topLeft, titleSafe.size, style = Stroke(1.dp.toPx()))
        }
    }
}

private fun Modifier.cameraPreviewViewport(state: CameraUiState): Modifier = if (state.hudVisible) {
    this.padding(top = CAMERA_OVERLAY_HEADER_HEIGHT, bottom = cameraHudHeight())
} else {
    this
}

internal fun offlinePreviewReadableSize(
    availableWidth: Dp,
    availableHeight: Dp,
    quarterTurn: Boolean,
): DpSize {
    val userWidth = if (quarterTurn) availableHeight else availableWidth
    val userHeight = if (quarterTurn) availableWidth else availableHeight
    return DpSize(
        width = minOf(if (quarterTurn) 520.dp else 320.dp, (userWidth - 32.dp).coerceAtLeast(1.dp)),
        height = minOf(176.dp, (userHeight - 32.dp).coerceAtLeast(1.dp)),
    )
}

private fun liveViewOverlayBottomPadding(state: CameraUiState): Dp =
    if (state.hudVisible) cameraHudHeight() + 12.dp else 12.dp

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
    CameraRotatingSlot(modifier.size(104.dp)) {
        Row(
            Modifier.background(Color(0xB8000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).background(AppRecord, CircleShape))
            Text(
                stringResource(R.string.recording_time, elapsed),
                color = AppText,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun BulbExposureIndicator(startedAtMillis: Long?, modifier: Modifier = Modifier) {
    var elapsedSeconds by remember(startedAtMillis) { mutableStateOf(0L) }
    LaunchedEffect(startedAtMillis) {
        val startedAt = startedAtMillis ?: SystemClock.elapsedRealtime()
        while (true) {
            elapsedSeconds = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L) / 1_000L
            delay(250L)
        }
    }
    val elapsed = "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
    CameraRotatingSlot(modifier.size(116.dp)) {
        Row(
            Modifier.background(Color(0xB8000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).background(AppWarning, CircleShape))
            Text(
                stringResource(R.string.bulb_exposure_time, elapsed),
                color = AppText,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun OfflinePreviewCopy(quarterTurn: Boolean) {
    val title = stringResource(R.string.offline_preview)
    val hint = stringResource(R.string.offline_preview_hint)
    val modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp, vertical = 12.dp)
        .testTag("offline-preview-content")
    Box(modifier) {
        if (quarterTurn) {
            Column(
                modifier = Modifier.fillMaxSize().testTag("offline-preview-inline"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                ) {
                    OfflinePreviewIcon(32.dp)
                    OfflinePreviewTitle(title, TextAlign.Start)
                }
                OfflinePreviewHint(
                    value = hint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().testTag("offline-preview-stacked"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                OfflinePreviewIcon(36.dp)
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OfflinePreviewTitle(title, TextAlign.Center)
                    OfflinePreviewHint(hint, TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun OfflinePreviewIcon(size: Dp) {
    Icon(
        painterResource(LucideR.drawable.lucide_ic_camera),
        contentDescription = null,
        tint = AppAccent,
        modifier = Modifier.size(size).testTag("offline-preview-icon"),
    )
}

@Composable
private fun OfflinePreviewTitle(value: String, textAlign: TextAlign) {
    CameraFittedCopy(
        value = value,
        color = AppText,
        fontWeight = FontWeight.SemiBold,
        maxFontSize = 16.sp,
        minFontSize = 12.sp,
        maxLines = 2,
        textAlign = textAlign,
        testTag = "offline-preview-title",
    )
}

@Composable
private fun OfflinePreviewHint(
    value: String,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
) {
    CameraFittedCopy(
        value = value,
        color = AppSubtleText,
        fontWeight = FontWeight.Normal,
        maxFontSize = 15.sp,
        minFontSize = 11.sp,
        maxLines = 3,
        textAlign = textAlign,
        testTag = "offline-preview-hint",
        modifier = modifier,
    )
}

@Composable
private fun CameraFittedCopy(
    value: String,
    color: Color,
    fontWeight: FontWeight,
    maxFontSize: TextUnit,
    minFontSize: TextUnit,
    maxLines: Int,
    textAlign: TextAlign,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val rotationQuadrant = cameraRotationQuadrant(LocalCameraControlTargetRotation.current)
    var fontSize by remember(value, configuration.fontScale, rotationQuadrant) {
        mutableStateOf(maxFontSize)
    }
    var unresolvedOverflow by remember(value, configuration.fontScale, rotationQuadrant) {
        mutableStateOf(false)
    }
    Text(
        text = value,
        color = color,
        fontSize = fontSize,
        lineHeight = fontSize * 1.22f,
        fontWeight = fontWeight,
        maxLines = maxLines,
        softWrap = true,
        overflow = TextOverflow.Clip,
        textAlign = textAlign,
        modifier = modifier.testTag(if (unresolvedOverflow) "$testTag-overflow" else testTag),
        onTextLayout = { result ->
            when {
                result.hasVisualOverflow && fontSize > minFontSize -> {
                    unresolvedOverflow = false
                    fontSize = (fontSize.value - 0.5f).coerceAtLeast(minFontSize.value).sp
                }
                else -> unresolvedOverflow = result.hasVisualOverflow
            }
        },
    )
}

@Composable
fun ExposureStrip(state: CameraUiState, actions: CameraActions, modifier: Modifier = Modifier) {
    val exposure = state.status?.exposure
    val available = !state.isBusy(CameraOperation.SETTING)
    Row(modifier.fillMaxWidth().height(84.dp), verticalAlignment = Alignment.CenterVertically) {
        ExposureCell(SettingPicker.ISO, stringResource(R.string.iso), exposure?.iso ?: "-", available && state.capabilities?.iso?.isNotEmpty() == true) { actions.openPicker(SettingPicker.ISO) }
        ExposureCell(SettingPicker.SHUTTER, stringResource(R.string.shutter), exposure?.shutter ?: "-", available && state.capabilities?.shutter?.isNotEmpty() == true) { actions.openPicker(SettingPicker.SHUTTER) }
        ExposureCell(SettingPicker.APERTURE, stringResource(R.string.aperture), exposure?.aperture ?: "-", available && state.capabilities?.aperture?.isNotEmpty() == true) { actions.openPicker(SettingPicker.APERTURE) }
        ExposureCell(SettingPicker.WHITE_BALANCE, stringResource(R.string.white_balance), localizedCameraValue("whitebalance", exposure?.whiteBalance ?: "-"), available && state.capabilities?.whiteBalance?.isNotEmpty() == true) { actions.openPicker(SettingPicker.WHITE_BALANCE) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ExposureCell(
    picker: SettingPicker,
    label: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .weight(1f)
            .fillMaxSize()
            .testTag("exposure-control-${picker.name}")
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        CameraRotatingSquareSlot(size = 72.dp) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .testTag("exposure-content-${picker.name}"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CameraHudText(
                    value = label,
                    color = AppMutedText,
                    fontWeight = FontWeight.Normal,
                    maxFontSize = 14.sp,
                    minFontSize = 10.sp,
                    modifier = Modifier.testTag("exposure-label-${picker.name}"),
                )
                CameraHudText(
                    value = value,
                    color = if (enabled) AppText else AppMutedText,
                    fontWeight = FontWeight.SemiBold,
                    maxFontSize = 17.sp,
                    minFontSize = 11.sp,
                    modifier = Modifier.testTag("exposure-value-${picker.name}"),
                )
            }
        }
    }
}

@Composable
fun CaptureButton(state: CameraUiState, actions: CameraActions) {
    val photo = state.captureMode == CaptureMode.PHOTO
    val bulb = photo && state.bulbMode
    val bulbActive = bulb && state.bulbExposureActive
    val recordingActive = !photo && state.status?.recording == true
    val supported = bulbActive || recordingActive || state.supports(
        when {
            bulb -> CameraFeature.BULB_EXPOSURE
            photo -> CameraFeature.STILL_CAPTURE
            else -> CameraFeature.VIDEO_RECORDING
        },
    )
    val description = when {
        bulbActive -> stringResource(R.string.stop_bulb_exposure)
        bulb -> stringResource(R.string.start_bulb_exposure)
        photo -> stringResource(R.string.capture_photo)
        recordingActive -> stringResource(R.string.stop_recording)
        else -> stringResource(R.string.start_recording)
    }
    val color = if (bulb) AppWarning else if (photo) AppText else AppRecord
    val operation = if (photo) CameraOperation.CAPTURE else CameraOperation.RECORDING
    val processing = state.isBusy(operation)
    val temperatureAllowed = when {
        bulbActive -> true
        photo -> state.stillCaptureTemperatureAllowed
        recordingActive -> true
        else -> state.movieRecordingTemperatureAllowed
    }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(state.captureFeedback) {
        if (state.captureFeedback == CaptureFeedback.SUCCESS) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    Box(
        Modifier.size(76.dp)
            .testTag("capture-button")
            .background(AppBackground, CircleShape)
            .clickable(enabled = supported && temperatureAllowed && !processing) {
                when {
                    bulb -> actions.toggleBulbExposure()
                    photo -> actions.captureStill()
                    else -> actions.toggleRecording()
                }
            }
            .semantics { contentDescription = description; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        if (processing) {
            CircularProgressIndicator(Modifier.size(48.dp), color = color, strokeWidth = 3.dp)
        } else {
            Box(
                Modifier.size(if (photo) 58.dp else 52.dp).background(
                    color,
                    if (bulbActive || recordingActive) RoundedCornerShape(8.dp) else CircleShape,
                )
            )
        }
    }
}

@Composable
fun ErrorBanner(error: String?, onDismiss: () -> Unit) {
    if (error == null) return
    CameraReadableSlot(
        width = 328.dp,
        height = 112.dp,
        modifier = Modifier
            .navigationBarsPadding()
            .padding(8.dp)
            .testTag("camera-error-rotation"),
        animateRotation = false,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF512326), RoundedCornerShape(6.dp))
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                error,
                color = AppText,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            ToolIconButton(LucideR.drawable.lucide_ic_x, stringResource(R.string.dismiss), onDismiss)
        }
    }
}
