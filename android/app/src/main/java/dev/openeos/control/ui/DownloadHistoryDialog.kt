package dev.openeos.control.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.openeos.control.R
import dev.openeos.control.data.DownloadHistoryDestination
import dev.openeos.control.data.DownloadHistoryEntry
import dev.openeos.control.data.DownloadHistoryOutcome
import dev.openeos.control.data.DownloadHistoryState
import dev.openeos.control.data.DownloadHistoryWarning
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** A process history remains accessible without a camera, picker grant, or media query. */
@Composable
internal fun DownloadHistoryDialog(
    state: DownloadHistoryState,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.warning) {
        if (state.warning != null) listState.scrollToItem(3)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = modifier.testTag("download-history-dialog").padding(12.dp).widthIn(max = 560.dp)
                .fillMaxWidth().heightIn(max = 640.dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(Modifier.padding(16.dp)) {
                // Header, explanations, warnings and records all scroll. The actions stay reachable
                // even when a narrow landscape window and large text leave little vertical room.
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().testTag("download-history-list"),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Text(stringResource(R.string.download_history_title), style = MaterialTheme.typography.titleLarge) }
                    item { Text(stringResource(R.string.download_history_scope)) }
                    item { Text(stringResource(R.string.download_history_outcome_hint), style = MaterialTheme.typography.bodySmall) }
                    state.warning?.let { warning ->
                        item {
                            Text(
                                stringResource(when (warning) {
                                    DownloadHistoryWarning.READ_FAILED -> R.string.download_history_warning_read
                                    DownloadHistoryWarning.WRITE_FAILED -> R.string.download_history_warning_write
                                    DownloadHistoryWarning.CLEAR_FAILED -> R.string.download_history_warning_clear
                                    DownloadHistoryWarning.STOPPED -> R.string.download_history_warning_stopped
                                }),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("download-history-warning"),
                            )
                        }
                    }
                    if (state.loading) {
                        item { Text(stringResource(R.string.download_history_loading)) }
                    } else if (state.entries.isEmpty()) {
                        item { Text(stringResource(R.string.download_history_empty)) }
                    }
                    items(state.entries, key = DownloadHistoryEntry::receiptId) { entry -> DownloadHistoryRow(entry) }
                }
                TextButton(
                    onClick = { confirmClear = true },
                    enabled = !state.loading && state.warning != DownloadHistoryWarning.STOPPED &&
                        (state.entries.isNotEmpty() || state.warning != null),
                    modifier = Modifier.fillMaxWidth().testTag("download-history-clear"),
                ) { Text(stringResource(R.string.download_history_clear)) }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("download-history-close")) {
                    Text(stringResource(R.string.download_history_close))
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            modifier = Modifier.testTag("download-history-clear-confirm"),
            title = { Text(stringResource(R.string.download_history_clear_title)) },
            text = { Text(stringResource(R.string.download_history_clear_confirmation), modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClear()
                        // A repeated failed clear can have the same warning value. It must still
                        // bring that result into view when the user has scrolled to older records.
                        scope.launch { listState.scrollToItem(if (state.warning != null) 3 else 0) }
                    },
                    modifier = Modifier.testTag("download-history-clear-confirm-button"),
                ) { Text(stringResource(R.string.download_history_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun DownloadHistoryRow(entry: DownloadHistoryEntry) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Column(
        Modifier.fillMaxWidth().testTag("download-history-entry-${entry.receiptId}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HorizontalDivider()
        Text(entry.filename, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(when (entry.outcome) {
            DownloadHistoryOutcome.IN_PROGRESS -> R.string.download_history_outcome_progress
            DownloadHistoryOutcome.COMPLETED -> R.string.download_history_outcome_completed
            DownloadHistoryOutcome.FAILED -> R.string.download_history_outcome_failed
            DownloadHistoryOutcome.CANCELLED -> R.string.download_history_outcome_cancelled
            DownloadHistoryOutcome.UNCONFIRMED -> R.string.download_history_outcome_unconfirmed
        }))
        if (entry.cleanupUnconfirmed) {
            Text(stringResource(R.string.media_save_cleanup_unconfirmed), color = AppWarning,
                modifier = Modifier.testTag("download-history-cleanup-${entry.receiptId}"))
        }
        Text(stringResource(when (entry.destination) {
            DownloadHistoryDestination.GALLERY -> R.string.download_history_destination_gallery
            DownloadHistoryDestination.DOCUMENT -> R.string.download_history_destination_document
            DownloadHistoryDestination.FOLDER -> R.string.download_history_destination_folder
        }), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.download_history_started, dateFormat.format(Date(entry.startedAtMillis))), style = MaterialTheme.typography.bodySmall)
        entry.finishedAtMillis?.let {
            Text(stringResource(R.string.download_history_finished, dateFormat.format(Date(it))), style = MaterialTheme.typography.bodySmall)
        }
    }
}
