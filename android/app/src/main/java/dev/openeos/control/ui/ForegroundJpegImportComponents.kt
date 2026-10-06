package dev.openeos.control.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.R as LucideR
import dev.openeos.control.R
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraTransport

internal val FOREGROUND_IMPORT_BAR_HEIGHT = 56.dp

/** The control screen reserves this space, rather than covering shutter or release controls. */
internal val CameraUiState.showForegroundJpegImportStatus: Boolean
    get() = foregroundImportCleanupUnconfirmed || foregroundJpegImport.phase != ForegroundImportPhase.OFF

/** Disconnected/debug screens have no import entry of their own. Reserve space, never overlay it. */
@Composable
internal fun ForegroundImportCleanupWarningHost(
    state: CameraUiState,
    actions: CameraActions,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        if (state.showForegroundJpegImportStatus && (!state.connected || state.uiMode == UiMode.DEBUG)) {
            ForegroundJpegImportEntry(state, actions, modifier = Modifier
                .testTag("foreground-import-global-warning")
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)))
        }
    }
}

private val ForegroundJpegImportStatus.failed: Boolean
    get() = stopReason !in setOf(null, ForegroundImportStopReason.USER,
        ForegroundImportStopReason.BACKGROUND, ForegroundImportStopReason.SESSION_CHANGED)

private val ForegroundJpegImportStatus.canStop: Boolean
    get() = phase in setOf(
        ForegroundImportPhase.BASELINING, ForegroundImportPhase.WATCHING,
        ForegroundImportPhase.WAITING, ForegroundImportPhase.SAVING,
    )

// A new camera object can have identical public descriptions; do not carry its old disclosure over.
private class ForegroundImportDialogSession(private val info: CameraInfo?, private val generation: Long) {
    override fun equals(other: Any?): Boolean = other is ForegroundImportDialogSession &&
        info === other.info && generation == other.generation
    override fun hashCode(): Int = 31 * System.identityHashCode(info) + generation.hashCode()
}

/** Opening this entry only explains the feature. Enable is a separate, explicit callback. */
@Composable
internal fun ForegroundJpegImportEntry(
    state: CameraUiState,
    actions: CameraActions,
    modifier: Modifier = Modifier,
    platformSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
) {
    key(ForegroundImportDialogSession(state.info, state.mediaSessionGeneration)) {
        var detailsVisible by remember { mutableStateOf(false) }
        val status = state.foregroundJpegImport
        val title = stringResource(R.string.foreground_import_title)
        val summary = foregroundImportSummary(status)
        val entryText = when {
            state.foregroundImportCleanupUnconfirmed && status.phase in FOREGROUND_IMPORT_ACTIVE_PHASES ->
                stringResource(R.string.foreground_import_cleanup_active_entry, summary)
            state.foregroundImportCleanupUnconfirmed -> stringResource(R.string.foreground_import_cleanup_entry)
            status.phase == ForegroundImportPhase.OFF -> title
            else -> summary
        }
        Surface(color = AppSurface, modifier = modifier.fillMaxWidth()) {
            Row(
                Modifier.height(FOREGROUND_IMPORT_BAR_HEIGHT).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { detailsVisible = true },
                    modifier = Modifier.weight(1f).testTag("foreground-import-entry")
                        .semantics { contentDescription = "$title. $entryText. $summary" },
                ) {
                    Text(
                        entryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (state.foregroundImportCleanupUnconfirmed ||
                            (status.phase == ForegroundImportPhase.STOPPED && status.failed)
                        ) AppWarning else AppText,
                    )
                }
                if (status.canStop || status.phase == ForegroundImportPhase.STOPPING) {
                    ToolIconButton(
                        LucideR.drawable.lucide_ic_square,
                        stringResource(if (status.phase == ForegroundImportPhase.STOPPING)
                            R.string.foreground_import_stopping else R.string.foreground_import_stop),
                        actions.stopForegroundJpegImport,
                        enabled = status.canStop,
                        testTag = "foreground-import-stop",
                    )
                }
            }
        }
        if (detailsVisible) {
            ForegroundJpegImportDialog(
                state, actions,
                onDismiss = { detailsVisible = false },
                platformSupported = platformSupported,
            )
        }
    }
}

/** All disclosure and actions share one scroll container, including in short large-text windows. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ForegroundJpegImportDialog(
    state: CameraUiState,
    actions: CameraActions,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    platformSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
) {
    val status = state.foregroundJpegImport
    val inactive = status.phase in setOf(ForegroundImportPhase.OFF, ForegroundImportPhase.STOPPED)
    val canEnable = state.canEnableForegroundJpegImport(platformSupported)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = modifier.testTag("foreground-import-dialog").padding(12.dp)
                .widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 640.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.foreground_import_title), style = MaterialTheme.typography.titleLarge)
                if (state.foregroundImportCleanupUnconfirmed) {
                    Surface(color = AppSurfaceHigh, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.foreground_import_cleanup_title),
                                style = MaterialTheme.typography.titleMedium, color = AppWarning,
                                modifier = Modifier.testTag("foreground-import-cleanup-warning"))
                            Text(stringResource(R.string.foreground_import_cleanup_warning))
                            Text(stringResource(R.string.foreground_import_cleanup_ack_disclosure),
                                modifier = Modifier.testTag("foreground-import-cleanup-ack-disclosure"))
                            TextButton(
                                onClick = actions.acknowledgeForegroundImportCleanupWarning,
                                enabled = !state.foregroundImportOwnerActive,
                                modifier = Modifier.testTag("foreground-import-cleanup-acknowledge"),
                            ) { Text(stringResource(R.string.foreground_import_cleanup_acknowledge)) }
                        }
                    }
                }
                if (status.phase != ForegroundImportPhase.OFF) {
                    Text(foregroundImportSummary(status), modifier = Modifier.testTag("foreground-import-summary"))
                    Text(stringResource(foregroundImportPhaseResource(status.phase)),
                        modifier = Modifier.testTag("foreground-import-phase"))
                    status.activeName?.let {
                        Text(stringResource(R.string.foreground_import_active_file, it))
                    }
                    Text(stringResource(R.string.foreground_import_counts,
                        status.baselineCount, status.knownCount, status.pendingCount,
                        status.completedCount, status.discardedCount),
                        modifier = Modifier.testTag("foreground-import-counts"))
                    status.stopReason?.let {
                        Text(stringResource(foregroundImportStopResource(it)),
                            color = if (it in setOf(ForegroundImportStopReason.USER,
                                ForegroundImportStopReason.BACKGROUND, ForegroundImportStopReason.SESSION_CHANGED)
                            ) AppSubtleText else AppWarning,
                            modifier = Modifier.testTag("foreground-import-stop-reason"))
                    }
                }
                Text(stringResource(R.string.foreground_import_destination, cameraGalleryPath(state.info?.model)),
                    modifier = Modifier.testTag("foreground-import-destination"))
                Text(stringResource(R.string.foreground_import_disclosure),
                    modifier = Modifier.testTag("foreground-import-disclosure"))
                Text(stringResource(R.string.foreground_import_baseline_disclosure))
                Text(stringResource(R.string.foreground_import_limits,
                    status.limits.baselineItems, status.limits.knownItems, status.limits.queuedItems),
                    modifier = Modifier.testTag("foreground-import-limits"))
                Text(stringResource(R.string.foreground_import_session_disclosure))
                if (inactive && !canEnable) {
                    val reason = when {
                        state.foregroundImportCleanupUnconfirmed -> R.string.foreground_import_requires_cleanup_ack
                        !platformSupported -> R.string.foreground_import_requires_android
                        !state.connected || state.previewMode || state.transport != CameraTransport.CCAPI_NETWORK ->
                            R.string.foreground_import_requires_connection
                        !state.supports(CameraFeature.MEDIA_BROWSER) || !state.supports(CameraFeature.MEDIA_DOWNLOAD) ->
                            R.string.foreground_import_requires_capabilities
                        else -> R.string.foreground_import_requires_idle
                    }
                    Text(stringResource(reason), color = AppWarning,
                        modifier = Modifier.testTag("foreground-import-unavailable"))
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("foreground-import-dismiss")) {
                        Text(stringResource(if (inactive) R.string.cancel else R.string.foreground_import_close))
                    }
                    if (inactive) {
                        TextButton(
                            onClick = { onDismiss(); actions.enableForegroundJpegImport() },
                            enabled = canEnable,
                            modifier = Modifier.testTag("foreground-import-start"),
                        ) { Text(stringResource(R.string.foreground_import_enable)) }
                    } else {
                        TextButton(
                            onClick = actions.stopForegroundJpegImport,
                            enabled = status.canStop,
                            modifier = Modifier.testTag("foreground-import-dialog-stop"),
                        ) {
                            Text(stringResource(if (status.phase == ForegroundImportPhase.STOPPING)
                                R.string.foreground_import_stopping else R.string.foreground_import_stop))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun foregroundImportSummary(status: ForegroundJpegImportStatus): String =
    if (status.phase == ForegroundImportPhase.OFF) stringResource(R.string.foreground_import_off)
    else stringResource(R.string.foreground_import_summary,
        stringResource(when (status.phase) {
            ForegroundImportPhase.OFF -> R.string.foreground_import_off
            ForegroundImportPhase.BASELINING -> R.string.foreground_import_preparing_short
            ForegroundImportPhase.WATCHING -> R.string.foreground_import_watching_short
            ForegroundImportPhase.WAITING -> R.string.foreground_import_waiting_short
            ForegroundImportPhase.SAVING -> R.string.foreground_import_saving_short
            ForegroundImportPhase.STOPPING -> R.string.foreground_import_stopping_short
            ForegroundImportPhase.STOPPED -> if (status.failed) R.string.foreground_import_failed_short
                else R.string.foreground_import_stopped_short
        }), status.pendingCount, status.completedCount)

private fun foregroundImportPhaseResource(phase: ForegroundImportPhase): Int = when (phase) {
    ForegroundImportPhase.OFF -> R.string.foreground_import_off
    ForegroundImportPhase.BASELINING -> R.string.foreground_import_preparing
    ForegroundImportPhase.WATCHING -> R.string.foreground_import_watching
    ForegroundImportPhase.WAITING -> R.string.foreground_import_waiting
    ForegroundImportPhase.SAVING -> R.string.foreground_import_saving
    ForegroundImportPhase.STOPPING -> R.string.foreground_import_stopping
    ForegroundImportPhase.STOPPED -> R.string.foreground_import_stopped
}

private fun foregroundImportStopResource(reason: ForegroundImportStopReason): Int = when (reason) {
    ForegroundImportStopReason.USER -> R.string.foreground_import_reason_user
    ForegroundImportStopReason.BACKGROUND -> R.string.foreground_import_reason_background
    ForegroundImportStopReason.SESSION_CHANGED -> R.string.foreground_import_reason_session
    ForegroundImportStopReason.UNSUPPORTED -> R.string.foreground_import_reason_unsupported
    ForegroundImportStopReason.INCOMPLETE_BASELINE -> R.string.foreground_import_reason_incomplete_baseline
    ForegroundImportStopReason.BASELINE_CHANGED -> R.string.foreground_import_reason_baseline_changed
    ForegroundImportStopReason.INCOMPLETE_SCAN -> R.string.foreground_import_reason_incomplete_scan
    ForegroundImportStopReason.BASELINE_LIMIT -> R.string.foreground_import_reason_baseline_limit
    ForegroundImportStopReason.QUEUE_LIMIT -> R.string.foreground_import_reason_queue_limit
    ForegroundImportStopReason.IDENTITY_LIMIT -> R.string.foreground_import_reason_identity_limit
    ForegroundImportStopReason.INVALID_SOURCE -> R.string.foreground_import_reason_invalid_source
    ForegroundImportStopReason.READ_FAILED -> R.string.foreground_import_reason_read_failed
    ForegroundImportStopReason.TRANSFER_FAILED -> R.string.foreground_import_reason_transfer_failed
    ForegroundImportStopReason.CLEANUP_UNCONFIRMED -> R.string.foreground_import_reason_cleanup
}
