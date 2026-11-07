package dev.openeos.control.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.openeos.control.R
import dev.openeos.control.data.DownloadHistoryOutcome

/** Every new draft starts empty; process replacement or a new session cannot restore mappings. */
@Composable
internal fun DownloadContinuationDialog(
    draft: DownloadContinuationDraft,
    onMap: (String, String?) -> Unit,
    onConfirmUnknown: (Boolean) -> Unit,
    onGallery: () -> Unit,
    onFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    var choosingRow by remember(draft.id) { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(12.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 640.dp)
            .testTag("download-continuation-dialog"), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp)) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("download-continuation-list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(stringResource(R.string.media_continuation_title), style = MaterialTheme.typography.titleLarge) }
                    item { Text(stringResource(R.string.media_continuation_explanation)) }
                    items(draft.rows, key = { it.rowId }) { row ->
                        Column(Modifier.testTag("download-continuation-row-${row.rowId}")) {
                            HorizontalDivider()
                            Text(row.filename, style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(when (row.receipt?.outcome) {
                                DownloadHistoryOutcome.UNCONFIRMED -> R.string.download_history_outcome_unconfirmed
                                DownloadHistoryOutcome.FAILED -> R.string.download_history_outcome_failed
                                DownloadHistoryOutcome.CANCELLED -> R.string.download_history_outcome_cancelled
                                else -> R.string.download_history_outcome_queued
                            }))
                            draft.mappings[row.rowId]?.let { source ->
                                Text(stringResource(R.string.media_continuation_mapping, source.name))
                                TextButton(onClick = { onMap(row.rowId, null) },
                                    modifier = Modifier.testTag("download-continuation-remove-${row.rowId}")) {
                                    Text(stringResource(R.string.media_continuation_remove))
                                }
                            }
                            TextButton(onClick = { choosingRow = row.rowId },
                                modifier = Modifier.testTag("download-continuation-map-${row.rowId}")) {
                                Text(stringResource(R.string.media_continuation_choose_source))
                            }
                        }
                    }
                    if (draft.requiresUnknownConfirmation) item {
                        Column {
                            Text(stringResource(R.string.media_continuation_unknown_warning), color = AppWarning)
                            Row {
                                Checkbox(draft.unknownConfirmed, onCheckedChange = onConfirmUnknown,
                                    modifier = Modifier.testTag("download-continuation-confirm-unknown"))
                                Text(stringResource(R.string.media_continuation_unknown_confirmation), Modifier.weight(1f))
                            }
                        }
                    }
                    item { Text(stringResource(R.string.media_continuation_destination_hint)) }
                }
                TextButton(onClick = onGallery,
                    enabled = draft.canConfirm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        draft.selectedItems.all(::canSaveMediaToGallery),
                    modifier = Modifier.fillMaxWidth().testTag("download-continuation-gallery")) {
                    Text(stringResource(R.string.media_continuation_gallery, draft.mappings.size))
                }
                TextButton(onClick = onFolder, enabled = draft.canConfirm,
                    modifier = Modifier.fillMaxWidth().testTag("download-continuation-folder")) {
                    Text(stringResource(R.string.media_continuation_folder, draft.mappings.size))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("download-continuation-cancel")) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
    choosingRow?.let { rowId ->
        Dialog(onDismissRequest = { choosingRow = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.padding(12.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 560.dp),
                shape = MaterialTheme.shapes.extraLarge) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.media_continuation_choose_source), style = MaterialTheme.typography.titleLarge)
                    LazyColumn(Modifier.weight(1f).testTag("download-continuation-sources")) {
                        items(draft.sources, key = { it.id }) { source ->
                            TextButton(
                                onClick = { onMap(rowId, source.id); choosingRow = null },
                                enabled = draft.mappings.none { (otherRow, item) -> otherRow != rowId && item.id == source.id },
                                modifier = Modifier.fillMaxWidth().testTag("download-continuation-source-${source.id}"),
                            ) {
                                Text(stringResource(R.string.media_continuation_source_label,
                                    draft.sources.indexOf(source) + 1, source.name, source.kind))
                            }
                        }
                    }
                    TextButton(onClick = { choosingRow = null }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}
