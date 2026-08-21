package dev.openeos.control.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AppAccent)

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
