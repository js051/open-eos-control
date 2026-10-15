package dev.openeos.control.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import dev.openeos.control.R
import dev.openeos.control.data.MediaMetadataField

@Composable
internal fun RowScope.MediaSelectionTopBar(
    selectedCount: Int,
    allDisplayedSelected: Boolean,
    busy: Boolean,
    downloadSupported: Boolean,
    sereinSupported: Boolean,
    metadataSupported: Boolean,
    deleteSupported: Boolean,
    onExit: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onOpenInSerein: () -> Unit,
    onDownload: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    ToolIconButton(
        LucideR.drawable.lucide_ic_x,
        stringResource(R.string.exit_media_selection),
        onExit,
    )
    Text(
        stringResource(R.string.media_selected_count, selectedCount),
        color = AppText,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    ToolIconButton(
        LucideR.drawable.lucide_ic_list_checks,
        stringResource(
            if (allDisplayedSelected) R.string.clear_media_selection else R.string.select_all_media,
        ),
        onToggleSelectAll,
        enabled = !busy,
    )
    if (sereinSupported) {
        ToolIconButton(
            LucideR.drawable.lucide_ic_palette,
            stringResource(R.string.open_selected_in_serein, selectedCount),
            onOpenInSerein,
            enabled = !busy,
            tint = AppAccent,
        )
    }
    if (downloadSupported) {
        ToolIconButton(
            LucideR.drawable.lucide_ic_download,
            stringResource(R.string.download_selected_media, selectedCount),
            onDownload,
            enabled = !busy,
        )
    }
    if (metadataSupported) {
        ToolIconButton(
            LucideR.drawable.lucide_ic_ellipsis_vertical,
            stringResource(R.string.edit_selected_media, selectedCount),
            onEdit,
            enabled = !busy,
        )
    }
    if (deleteSupported) {
        ToolIconButton(
            LucideR.drawable.lucide_ic_trash_2,
            stringResource(R.string.delete_selected_media, selectedCount),
            onDelete,
            enabled = !busy,
            tint = AppRecord,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun MediaBatchMetadataSheet(
    itemCount: Int,
    busy: Boolean,
    protectSupported: Boolean,
    archiveSupported: Boolean,
    ratingSupported: Boolean,
    rotationSupported: Boolean,
    onDismiss: () -> Unit,
    onProtect: (Boolean) -> Unit,
    onArchive: (Boolean) -> Unit,
    onRate: (Int) -> Unit,
    onRotate: (Int) -> Unit,
    downloadSupported: Boolean = false,
    onSaveToFolder: () -> Unit = {},
    hiddenCount: Int = 0,
    unloadedCount: Int = 0,
    receipt: MediaMetadataBatchResult? = null,
    onCancel: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppSurface,
        contentColor = AppText,
    ) {
        Column(
            Modifier.testTag("media-batch-metadata-sheet")
                .fillMaxWidth().fillMaxHeight(0.9f).verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.edit_selected_media, itemCount),
                color = AppText,
                fontWeight = FontWeight.Bold,
            )
            Text(stringResource(R.string.media_metadata_frozen_scope, itemCount, hiddenCount, unloadedCount), color = AppSubtleText)
            if (receipt != null) MediaMetadataBatchReceipt(receipt, onCancel)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AppAccent)

            if (downloadSupported) {
                TextButton(onClick = onSaveToFolder, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_folder), null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.media_save_to_folder))
                }
            }

            if (protectSupported) {
                Text(stringResource(R.string.media_protection), color = AppText, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BatchMediaActionButton(
                        icon = LucideR.drawable.lucide_ic_lock,
                        text = stringResource(R.string.protect_selected_media),
                        enabled = !busy,
                        onClick = { onProtect(true) },
                        modifier = Modifier.weight(1f),
                    )
                    BatchMediaActionButton(
                        icon = LucideR.drawable.lucide_ic_lock_open,
                        text = stringResource(R.string.unprotect_selected_media),
                        enabled = !busy,
                        onClick = { onProtect(false) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (archiveSupported) {
                Text(stringResource(R.string.media_archive), color = AppText, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BatchMediaActionButton(
                        icon = LucideR.drawable.lucide_ic_archive,
                        text = stringResource(R.string.archive_selected_media),
                        enabled = !busy,
                        onClick = { onArchive(true) },
                        modifier = Modifier.weight(1f),
                    )
                    BatchMediaActionButton(
                        icon = LucideR.drawable.lucide_ic_archive_restore,
                        text = stringResource(R.string.unarchive_selected_media),
                        enabled = !busy,
                        onClick = { onArchive(false) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (ratingSupported) {
                Text(stringResource(R.string.media_rating), color = AppText, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ToolIconButton(
                        LucideR.drawable.lucide_ic_star_off,
                        stringResource(R.string.clear_selected_media_rating),
                        { onRate(0) },
                        enabled = !busy,
                        tint = AppSubtleText,
                    )
                    (1..5).forEach { rating ->
                        ToolIconButton(
                            LucideR.drawable.lucide_ic_star,
                            stringResource(R.string.set_selected_media_rating, rating),
                            { onRate(rating) },
                            enabled = !busy,
                            tint = AppWarning,
                        )
                    }
                }
            }

            if (rotationSupported) {
                Text(stringResource(R.string.media_rotation), color = AppText, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0, 90, 180, 270).forEach { degrees ->
                        TextButton(
                            onClick = { onRotate(degrees) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) {
                            Text(stringResource(R.string.rotation_degrees_short, degrees), color = AppText)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchMediaActionButton(
    icon: Int,
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun MediaMetadataBatchReceipt(receipt: MediaMetadataBatchResult, onCancel: () -> Unit, rowTagPrefix: String = "media-metadata-row") {
    Text(
        stringResource(R.string.media_metadata_summary, receipt.confirmed, receipt.uncertain, receipt.failedNotSent, receipt.notStarted, receipt.inFlight),
        modifier = Modifier.testTag("media-metadata-receipt"),
        color = if (receipt.uncertain > 0 || receipt.failedNotSent > 0 || receipt.notStarted > 0) AppWarning else AppSuccess,
    )
    if (receipt.running) {
        TextButton(onClick = onCancel, modifier = Modifier.testTag("media-metadata-cancel")) {
            Text(stringResource(R.string.media_metadata_cancel))
        }
    }
    if (receipt.uncertain > 0) Text(stringResource(R.string.media_metadata_uncertain_help), color = AppWarning)
    receipt.rows.firstOrNull()?.request?.target?.let { target ->
        val label = stringResource(when (target.field) {
            MediaMetadataField.PROTECTION -> R.string.media_protection
            MediaMetadataField.ARCHIVE -> R.string.media_archive
            MediaMetadataField.RATING -> R.string.media_rating
            MediaMetadataField.ROTATION -> R.string.media_rotation
        })
        val value = when (target.field) {
            MediaMetadataField.PROTECTION, MediaMetadataField.ARCHIVE ->
                stringResource(if (target.value == 1) R.string.media_metadata_enabled else R.string.media_metadata_disabled)
            MediaMetadataField.ROTATION -> stringResource(R.string.rotation_degrees_short, target.value)
            MediaMetadataField.RATING -> target.value.toString()
        }
        Text(stringResource(R.string.media_metadata_target, label, value), color = AppSubtleText)
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
        receipt.rows.forEach { row ->
            val status = stringResource(when (row.outcome) {
                MediaMetadataOutcome.IN_FLIGHT -> R.string.media_metadata_in_flight
                MediaMetadataOutcome.NOT_STARTED -> R.string.media_metadata_not_started
                MediaMetadataOutcome.CONFIRMED -> R.string.media_metadata_confirmed
                MediaMetadataOutcome.UNCERTAIN -> R.string.media_metadata_uncertain
                MediaMetadataOutcome.FAILED_NOT_SENT -> R.string.media_metadata_not_sent
            })
            val reason = when (row.reason) {
                MediaMetadataReason.NONE -> null
                MediaMetadataReason.UNLOADED -> R.string.media_metadata_reason_unloaded
                MediaMetadataReason.UNKNOWN_METADATA -> R.string.media_metadata_reason_unknown
                MediaMetadataReason.NOT_WRITABLE -> R.string.media_metadata_reason_not_writable
                MediaMetadataReason.UNSUPPORTED -> R.string.media_metadata_reason_unsupported
                MediaMetadataReason.CANCELLED -> R.string.media_metadata_reason_cancelled
                MediaMetadataReason.OWNER_CHANGED -> R.string.media_metadata_reason_owner
                MediaMetadataReason.READBACK_UNCONFIRMED -> R.string.media_metadata_reason_readback
            }
            val detail = if (reason == null || row.outcome == MediaMetadataOutcome.IN_FLIGHT) status
                else "$status · ${stringResource(reason)}"
            Text(stringResource(R.string.media_metadata_row, row.request.name, row.request.id, detail),
                color = if (row.outcome == MediaMetadataOutcome.UNCERTAIN) AppWarning else AppSubtleText,
                modifier = Modifier.testTag("$rowTagPrefix-${row.request.id}"))
        }
    }
}
