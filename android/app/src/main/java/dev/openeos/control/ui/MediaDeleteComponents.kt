package dev.openeos.control.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import dev.openeos.control.R
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.MediaDeleteOutcome

@Composable
internal fun MediaDeleteConfirmation(selection: MediaDeleteSelection, single: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (single) stringResource(R.string.delete_media_title)
            else stringResource(R.string.delete_selected_media_title, selection.size)) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text(if (single) stringResource(R.string.delete_media_confirmation, selection.first().name)
                else stringResource(R.string.delete_selected_media_confirmation, selection.size))
            DeleteOwnerDetails(selection.owner)
            if (selection.hiddenCount > 0) Text(stringResource(R.string.media_hidden_selected, selection.hiddenCount))
            if (selection.unloadedCount > 0) Text(stringResource(R.string.media_delete_unloaded_count, selection.unloadedCount), color = AppWarning)
            selection.requests.forEach { request ->
                Text(request.name)
                Text(request.id)
                Text(deleteSourceText(request.source))
                when {
                    request.protected == true -> Text(stringResource(R.string.media_delete_protected), color = AppWarning)
                    request.writable == false -> Text(stringResource(R.string.media_delete_not_writable), color = AppWarning)
                    request.protected == null || request.writable == null -> Text(stringResource(R.string.media_delete_eligibility_unknown), color = AppSubtleText)
                }
            }
        } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete), color = AppRecord) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun MediaDeleteReceipts(state: CameraUiState, actions: CameraActions) {
    var historyVisible by remember { mutableStateOf(false) }
    state.mediaDeleteReceipt?.let { receipt ->
        DeleteReceipt(receipt, state, actions)
    }
    val history = state.mediaDeleteHistory.filterNot { it.attemptId == state.mediaDeleteReceipt?.attemptId }
    if (history.isNotEmpty()) {
        TextButton(onClick = { historyVisible = !historyVisible }, modifier = Modifier.testTag("media-delete-history")) {
            Text(stringResource(R.string.media_delete_history, history.size))
        }
        if (historyVisible) Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            history.forEach { DeleteReceipt(it, state, actions) }
        }
    }
}

@Composable
private fun DeleteReceipt(receipt: MediaDeleteReceipt, state: CameraUiState, actions: CameraActions) {
    var details by remember(receipt.attemptId) { mutableStateOf(false) }
    val current = state.connected && receipt.owner.matches(state.info, state.mediaSessionGeneration)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("media-delete-receipt-${receipt.attemptId}")) {
        Text(stringResource(R.string.media_delete_summary,
            receipt.rows.count { it.outcome == MediaDeleteOutcome.CONFIRMED },
            receipt.rows.count { it.outcome == MediaDeleteOutcome.REJECTED },
            receipt.rows.count { it.outcome == MediaDeleteOutcome.UNCERTAIN },
            receipt.rows.count { it.outcome == MediaDeleteOutcome.NOT_SENT },
            receipt.rows.count { it.outcome == MediaDeleteOutcome.NOT_STARTED },
            receipt.rows.count { it.outcome == MediaDeleteOutcome.SKIPPED }))
        Text(stringResource(R.string.media_delete_recovery), color = AppWarning)
        if (!current) Text(stringResource(R.string.media_delete_old_owner), color = AppWarning)
        if (receipt.running && current) TextButton(onClick = { actions.cancelMediaDeletion(receipt.owner) }) {
            Text(stringResource(R.string.media_delete_stop))
        }
        TextButton(onClick = { details = !details }) { Text(stringResource(R.string.media_delete_details)) }
        if (details) Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            DeleteOwnerDetails(receipt.owner)
            Text(receipt.attemptId)
            receipt.rows.forEach { row ->
                Text(row.request.name)
                Text(row.request.id)
                Text(deleteSourceText(row.request.source))
                Text(deleteOutcomeText(row), color = if (row.outcome == MediaDeleteOutcome.CONFIRMED) AppSuccess else AppWarning)
                if (row.reason != MediaDeleteReason.NONE) Text(deleteReasonText(row.reason))
            }
        }
        if (current && !receipt.running && state.supports(CameraFeature.MEDIA_BROWSER)) TextButton(
            enabled = !state.isBusy(CameraOperation.MEDIA) && !state.mediaLibraryLoading,
            onClick = { actions.inspectMediaDeletion(receipt.owner) },
        ) { Text(stringResource(R.string.media_delete_inspect)) }
    }
}

@Composable private fun deleteSourceText(source: MediaDeleteSource): String = stringResource(when (source) {
    MediaDeleteSource.CAMERA_CARD -> R.string.media_delete_source_camera
    MediaDeleteSource.ANDROID_PRIVATE -> R.string.media_delete_source_android
    MediaDeleteSource.DESKTOP_HOST -> R.string.media_delete_source_desktop
    MediaDeleteSource.UNKNOWN -> R.string.media_delete_source_unknown
})
@Composable private fun deleteOutcomeText(row: MediaDeleteRow): String = stringResource(if (row.inFlight) R.string.media_delete_sending else when (row.outcome) {
    MediaDeleteOutcome.CONFIRMED -> R.string.media_delete_confirmed
    MediaDeleteOutcome.REJECTED -> R.string.media_delete_rejected
    MediaDeleteOutcome.UNCERTAIN -> R.string.media_delete_uncertain
    MediaDeleteOutcome.NOT_SENT -> R.string.media_delete_not_sent
    MediaDeleteOutcome.NOT_STARTED -> R.string.media_delete_not_started
    MediaDeleteOutcome.SKIPPED -> R.string.media_delete_skipped
})
@Composable private fun deleteReasonText(reason: MediaDeleteReason): String = stringResource(when (reason) {
    MediaDeleteReason.PROTECTED -> R.string.media_delete_protected
    MediaDeleteReason.NOT_WRITABLE -> R.string.media_delete_not_writable
    MediaDeleteReason.UNLOADED -> R.string.media_delete_unloaded
    MediaDeleteReason.ITEM_CHANGED -> R.string.media_delete_item_changed
    MediaDeleteReason.UNSUPPORTED -> R.string.media_delete_unsupported
    MediaDeleteReason.SOURCE_UNKNOWN -> R.string.media_delete_source_unknown
    MediaDeleteReason.OWNER_CHANGED -> R.string.media_delete_old_owner
    MediaDeleteReason.CANCELLED -> R.string.media_delete_stopped
    else -> R.string.media_delete_recovery
})

@Composable
private fun DeleteOwnerDetails(owner: MediaDeleteOwner) {
    val info = owner.connection as? CameraInfo
    Text(stringResource(R.string.media_delete_owner, info?.model.orEmpty(), owner.generation))
    info?.bridgeEndpoint?.let { endpoint ->
        // Defense in depth for historical/display-only rows; never render raw URL credentials or query.
        dev.openeos.control.data.mediaDeleteEndpoint(endpoint)?.let { safe ->
            Text(stringResource(R.string.media_delete_bridge_endpoint, safe, info.bridgeEngine ?: "unknown"))
        }
    }
}
