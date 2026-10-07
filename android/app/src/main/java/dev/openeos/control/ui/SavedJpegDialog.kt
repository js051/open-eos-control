package dev.openeos.control.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.openeos.control.R

/** Local Gallery references and handoff feedback, outside every camera-session UI subtree. */
@Composable
internal fun SavedJpegDialog(
    state: SavedJpegUiState,
    handoff: CameraImportHandoffState<CameraImportHandoffSession>,
    automaticImportActive: Boolean,
    onToggle: (DeliveredJpegId, Boolean) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onRetryCleanup: () -> Unit,
    onRecheck: (DeliveredJpegId) -> Unit,
    onStopAutomaticImport: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val active = handoff.active
    val localPhase = active?.takeIf { it.origin == CameraImportHandoffOrigin.SAVED_JPEG }?.phase
    val cleanupUnconfirmed = state.cleanupUnconfirmed || active?.cleanupUnconfirmed == true
    val cameraOutcome = active?.takeIf { it.origin == CameraImportHandoffOrigin.CAMERA }?.outcome
        ?: handoff.lastResult?.takeIf { active == null && it.origin == CameraImportHandoffOrigin.CAMERA }?.outcome
    val selectedCount = state.rows.count { it.id in state.selectedIds && it.id !in state.unavailableIds }
    LaunchedEffect(state.outcome, cleanupUnconfirmed, localPhase) {
        if (state.outcome != null || cleanupUnconfirmed || localPhase != null) {
            listState.scrollToItem(2)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = modifier.padding(12.dp).widthIn(max = 560.dp).fillMaxWidth()
                .heightIn(max = 720.dp).testTag("saved-jpegs-dialog"),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(Modifier.padding(16.dp)) {
                // All long text, controls and records share the scroll area. Only the concise
                // selection count and Close action stay fixed in short landscape windows.
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("saved-jpegs-list"),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "title") {
                        Text(stringResource(R.string.saved_jpegs_title), style = MaterialTheme.typography.titleLarge)
                    }
                    item(key = "scope") {
                        Text(stringResource(R.string.saved_jpegs_scope), style = MaterialTheme.typography.bodyMedium)
                    }
                    item(key = "status") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (automaticImportActive) {
                                Text(stringResource(R.string.saved_jpegs_stop_required), color = AppWarning)
                                Button(
                                    onClick = onStopAutomaticImport,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                        .testTag("saved-jpegs-stop-import"),
                                ) { Text(stringResource(R.string.saved_jpegs_stop_import)) }
                            }
                            if (active != null && localPhase == null) {
                                Text(stringResource(R.string.saved_jpegs_other_handoff), color = AppWarning)
                            }
                            localPhase?.let { phase ->
                                SavedJpegHandoffStatus(phase, state.progress, onCancel)
                            }
                            cameraOutcome?.let { outcome ->
                                Text(stringResource(R.string.saved_jpegs_camera_result), style = MaterialTheme.typography.titleSmall)
                                outcome.summary?.let { summary ->
                                    Text(stringResource(R.string.saved_jpegs_result,
                                        summary.imported, summary.duplicates, summary.failed, summary.cancelled),
                                        modifier = Modifier.testTag("saved-jpegs-camera-result"))
                                }
                                outcome.issue?.let { issue -> Text(stringResource(savedJpegIssueText(issue))) }
                            }
                            state.outcome?.summary?.let { summary ->
                                Text(
                                    stringResource(
                                        R.string.saved_jpegs_result,
                                        summary.imported, summary.duplicates, summary.failed, summary.cancelled,
                                    ),
                                    color = if (summary.failed == 0 && summary.cancelled == 0) AppSuccess else AppWarning,
                                    modifier = Modifier.testTag("saved-jpegs-result")
                                        .semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            }
                            state.outcome?.issue?.let { issue ->
                                Text(
                                    stringResource(savedJpegIssueText(issue)),
                                    color = if (issue == CameraImportHandoffIssue.CANCELLED) AppSubtleText
                                        else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag("saved-jpegs-issue")
                                        .semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            }
                            if (cleanupUnconfirmed) {
                                Text(
                                    stringResource(R.string.saved_jpegs_cleanup_warning),
                                    color = AppWarning,
                                    modifier = Modifier.testTag("saved-jpegs-cleanup-warning")
                                        .semantics { liveRegion = LiveRegionMode.Polite },
                                )
                                TextButton(
                                    onClick = onRetryCleanup,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                        .testTag("saved-jpegs-retry-cleanup"),
                                ) { Text(stringResource(R.string.saved_jpegs_retry_cleanup)) }
                            }
                        }
                    }
                    item(key = "send") {
                        Button(
                            onClick = onSend,
                            enabled = selectedCount > 0 && !handoff.busy && !automaticImportActive,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("saved-jpegs-send"),
                        ) { Text(stringResource(R.string.saved_jpegs_send, selectedCount)) }
                    }
                    item(key = "clear") {
                        TextButton(
                            onClick = { confirmClear = true },
                            enabled = state.rows.isNotEmpty() || state.registrationUnavailable || state.evictedCount > 0L,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("saved-jpegs-clear"),
                        ) { Text(stringResource(R.string.saved_jpegs_clear)) }
                    }
                    item(key = "list-notices") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.registrationUnavailable) {
                                Text(
                                    stringResource(R.string.saved_jpegs_registration_warning),
                                    color = AppWarning,
                                    modifier = Modifier.testTag("saved-jpegs-registration-warning"),
                                )
                            }
                            if (state.evictedCount > 0L) {
                                Text(
                                    stringResource(R.string.saved_jpegs_eviction_notice),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("saved-jpegs-eviction-notice"),
                                )
                            }
                            if (state.rows.isEmpty()) {
                                Text(stringResource(R.string.saved_jpegs_empty), modifier = Modifier.testTag("saved-jpegs-empty"))
                            }
                        }
                    }
                    items(state.rows, key = { it.id.value }) { row ->
                        SavedJpegRow(
                            row = row,
                            selected = row.id in state.selectedIds && row.id !in state.unavailableIds,
                            enabled = !handoff.busy && row.id !in state.unavailableIds,
                            unavailable = row.id in state.unavailableIds,
                            rechecking = state.recheckingId == row.id,
                            recheckEnabled = !handoff.busy && !automaticImportActive && state.recheckingId == null,
                            onToggle = { onToggle(row.id, it) },
                            onRecheck = { onRecheck(row.id) },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.saved_jpegs_selected_count, selectedCount, state.rows.size),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(end = 8.dp).testTag("saved-jpegs-selection-count"),
                    )
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("saved-jpegs-close"),
                    ) { Text(stringResource(R.string.download_history_close)) }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            modifier = Modifier.testTag("saved-jpegs-clear-confirm"),
            title = { Text(stringResource(R.string.saved_jpegs_clear_title)) },
            text = {
                Text(
                    stringResource(R.string.saved_jpegs_clear_confirmation),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmClear = false; onClear() },
                    modifier = Modifier.testTag("saved-jpegs-clear-confirm-button"),
                ) { Text(stringResource(R.string.saved_jpegs_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SavedJpegHandoffStatus(
    phase: CameraImportHandoffPhase,
    progress: SavedJpegProgress?,
    onCancel: () -> Unit,
) {
    Text(
        stringResource(when (phase) {
            CameraImportHandoffPhase.PREPARING -> R.string.saved_jpegs_preparing
            CameraImportHandoffPhase.READY -> R.string.saved_jpegs_ready
            CameraImportHandoffPhase.AWAITING_RESULT -> R.string.saved_jpegs_awaiting_result
            CameraImportHandoffPhase.READING_RECEIPT -> R.string.saved_jpegs_reading_receipt
            CameraImportHandoffPhase.CLEANING -> R.string.saved_jpegs_cleaning
        }),
        modifier = Modifier.testTag("saved-jpegs-phase"),
    )
    if (phase == CameraImportHandoffPhase.PREPARING) {
        progress?.let {
            Text(
                stringResource(R.string.saved_jpegs_progress, it.completedItems, it.totalItems),
                modifier = Modifier.testTag("saved-jpegs-progress-count"),
            )
            if (it.filename.isNotBlank()) Text(it.filename, style = MaterialTheme.typography.bodySmall)
            val total = it.totalBytes?.takeIf { bytes -> bytes > 0L }
            if (total == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.saved_jpegs_progress_bytes, mediaByteSizeLabel(it.bytesTransferred).orEmpty()))
            } else {
                LinearProgressIndicator(
                    progress = { (it.bytesTransferred.toDouble() / total).coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(
                    R.string.saved_jpegs_progress_bytes_total,
                    mediaByteSizeLabel(it.bytesTransferred).orEmpty(), mediaByteSizeLabel(total).orEmpty(),
                ))
            }
        } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        TextButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("saved-jpegs-cancel"),
        ) { Text(stringResource(R.string.saved_jpegs_cancel_preparation)) }
    }
}

@Composable
private fun SavedJpegRow(
    row: DeliveredJpegRow,
    selected: Boolean,
    enabled: Boolean,
    unavailable: Boolean,
    rechecking: Boolean,
    recheckEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onRecheck: () -> Unit,
) {
    Column {
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = onToggle)
                .padding(vertical = 8.dp).testTag("saved-jpegs-entry-${row.id.value}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
            Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.filename, style = MaterialTheme.typography.titleSmall)
                Text(row.cameraModel, style = MaterialTheme.typography.bodySmall)
                Text(
                    mediaCaptureTimeLabel(row.captureTime) ?: stringResource(R.string.media_unknown_date),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(mediaByteSizeLabel(row.byteLength).orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (unavailable) {
            Text(
                stringResource(R.string.saved_jpegs_row_unavailable),
                color = AppWarning,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("saved-jpegs-unavailable-${row.id.value}"),
            )
            TextButton(
                onClick = onRecheck,
                enabled = recheckEnabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("saved-jpegs-recheck-${row.id.value}"),
            ) {
                Text(stringResource(if (rechecking) R.string.saved_jpegs_rechecking else R.string.saved_jpegs_recheck))
            }
        }
    }
}

@StringRes
private fun savedJpegIssueText(issue: CameraImportHandoffIssue): Int = when (issue) {
    CameraImportHandoffIssue.EMPTY_SELECTION -> R.string.saved_jpegs_issue_empty
    CameraImportHandoffIssue.STALE_SELECTION -> R.string.saved_jpegs_issue_stale
    CameraImportHandoffIssue.AUTOMATIC_IMPORT_ACTIVE -> R.string.saved_jpegs_stop_required
    CameraImportHandoffIssue.HANDOFF_BUSY -> R.string.saved_jpegs_other_handoff
    CameraImportHandoffIssue.SEREIN_UNAVAILABLE -> R.string.saved_jpegs_issue_serein_unavailable
    CameraImportHandoffIssue.PREPARATION_FAILED -> R.string.saved_jpegs_issue_preparation
    CameraImportHandoffIssue.SOURCE_UNAVAILABLE -> R.string.saved_jpegs_issue_source
    CameraImportHandoffIssue.INSUFFICIENT_SPACE -> R.string.saved_jpegs_issue_space
    CameraImportHandoffIssue.CANCELLED -> R.string.saved_jpegs_issue_cancelled
    CameraImportHandoffIssue.LAUNCH_FAILED -> R.string.saved_jpegs_issue_launch
    CameraImportHandoffIssue.RECEIPT_MISSING -> R.string.saved_jpegs_issue_receipt_missing
    CameraImportHandoffIssue.RECEIPT_INVALID -> R.string.saved_jpegs_issue_receipt_invalid
}
