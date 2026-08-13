package dev.openeos.control.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import dev.openeos.control.R
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaTransferProgress
import java.util.Locale

@Composable
fun MediaScreen(state: CameraUiState, actions: CameraActions) {
    var pendingDownload by remember { mutableStateOf<CameraMediaItem?>(null) }
    var pendingDelete by remember { mutableStateOf<CameraMediaItem?>(null) }
    var activeMetadataItemId by remember { mutableStateOf<String?>(null) }
    var mediaFilter by remember { mutableStateOf(MediaFilter.ALL) }
    var mediaSort by remember { mutableStateOf(MediaSort.NEWEST) }
    val displayedItems = remember(state.mediaItems, mediaFilter, mediaSort) {
        mediaItemsForDisplay(state.mediaItems, mediaFilter, mediaSort)
    }
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { destination ->
        val item = pendingDownload
        pendingDownload = null
        if (destination != null && item != null) actions.downloadMedia(item, destination)
    }
    val openUploadDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        if (source != null) actions.uploadMedia(source)
    }

    state.mediaPreviewItem?.let { item ->
        val previewIndex = displayedItems.indexOfFirst { it.id == item.id }
        MediaViewerDialog(
            item = item,
            bytes = state.mediaPreviewBytes,
            streamSource = state.mediaStreamSource,
            loading = state.mediaPreviewLoading,
            canMovePrevious = previewIndex > 0,
            canMoveNext = previewIndex in 0 until displayedItems.lastIndex,
            onPrevious = { actions.previewAdjacentMedia(displayedItems, -1) },
            onNext = { actions.previewAdjacentMedia(displayedItems, 1) },
            downloadEnabled = !state.previewMode && state.supports(CameraFeature.MEDIA_DOWNLOAD),
            onDownload = {
                pendingDownload = item
                createDocument.launch(item.name)
            },
            onDismiss = actions.closeMediaPreview,
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_media_title)) },
            text = { Text(stringResource(R.string.delete_media_confirmation, item.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        actions.deleteMedia(item)
                    },
                ) {
                    Text(stringResource(R.string.delete), color = AppRecord)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    activeMetadataItemId?.let { itemId ->
        val item = state.mediaItems.firstOrNull { it.id == itemId }
        if (item != null) {
            val metadataSupported = state.supports(CameraFeature.MEDIA_PROTECT) ||
                state.supports(CameraFeature.MEDIA_ARCHIVE) ||
                (state.supports(CameraFeature.MEDIA_RATING) && item.ratingWritable != false) ||
                state.supports(CameraFeature.MEDIA_ROTATE)
            LaunchedEffect(itemId, metadataSupported) {
                if (metadataSupported) actions.loadMediaInfo(item)
            }
            MediaMetadataSheet(
                item = item,
                busy = state.isBusy(CameraOperation.MEDIA),
                protectSupported = state.supports(CameraFeature.MEDIA_PROTECT) && item.protected != null,
                archiveSupported = state.supports(CameraFeature.MEDIA_ARCHIVE) && item.archived != null,
                ratingSupported = state.supports(CameraFeature.MEDIA_RATING) && item.ratingWritable != false,
                rotationSupported = state.supports(CameraFeature.MEDIA_ROTATE),
                downloadSupported = !state.previewMode && state.supports(CameraFeature.MEDIA_DOWNLOAD),
                deleteSupported = !state.previewMode && state.supports(CameraFeature.MEDIA_DELETE),
                onDismiss = { activeMetadataItemId = null },
                onProtect = { actions.setMediaProtection(item, it) },
                onArchive = { actions.setMediaArchived(item, it) },
                onRate = { actions.setMediaRating(item, it) },
                onRotate = { actions.setMediaRotation(item, it) },
                onDownload = {
                    activeMetadataItemId = null
                    pendingDownload = item
                    createDocument.launch(item.name)
                },
                onDelete = {
                    activeMetadataItemId = null
                    pendingDelete = item
                },
            )
        } else {
            LaunchedEffect(itemId) { activeMetadataItemId = null }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(AppBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolIconButton(
                LucideR.drawable.lucide_ic_arrow_left,
                stringResource(R.string.back_to_camera),
                { actions.setUiMode(UiMode.CONTROL) },
            )
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.camera_media), color = AppText, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.media_item_count, state.mediaItems.size),
                    color = AppSubtleText,
                    maxLines = 1,
                )
            }
            if (state.supports(CameraFeature.MEDIA_UPLOAD)) {
                ToolIconButton(
                    LucideR.drawable.lucide_ic_upload,
                    stringResource(R.string.upload_media),
                    { openUploadDocument.launch(arrayOf("image/*", "video/*", "application/octet-stream")) },
                    enabled = !state.previewMode && !state.isBusy(CameraOperation.MEDIA),
                )
            }
            MediaSortButton(mediaSort, onSort = { mediaSort = it })
            ToolIconButton(
                LucideR.drawable.lucide_ic_refresh_cw,
                stringResource(R.string.refresh_media),
                actions.refreshMedia,
                enabled = !state.previewMode && !state.mediaLibraryLoading && !state.isBusy(CameraOperation.MEDIA),
            )
        }

        MediaFilterBar(mediaFilter, state.mediaItems, onSelected = { mediaFilter = it })

        if (state.isBusy(CameraOperation.MEDIA) || state.mediaLibraryLoading) {
            val progress = state.mediaUploadProgress ?: state.mediaDownloadProgress
            val totalBytes = progress?.totalBytes
            if (progress != null && totalBytes != null && totalBytes > 0L) {
                LinearProgressIndicator(
                    progress = { (progress.bytesTransferred.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = AppAccent,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = AppAccent)
            }
        }

        state.activeMediaDownloadName?.let { name ->
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.downloading_media, name),
                        color = AppText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.mediaDownloadProgress?.let { progress ->
                        Text(formatMediaProgress(progress), color = AppSubtleText, maxLines = 1)
                    }
                }
                ToolIconButton(
                    LucideR.drawable.lucide_ic_x,
                    stringResource(R.string.cancel_media_download),
                    actions.cancelMediaDownload,
                )
            }
        }

        state.activeMediaUploadName?.let { name ->
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.uploading_media, name),
                        color = AppText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.mediaUploadProgress?.let { progress ->
                        Text(formatMediaProgress(progress), color = AppSubtleText, maxLines = 1)
                    }
                }
                ToolIconButton(
                    LucideR.drawable.lucide_ic_x,
                    stringResource(R.string.cancel_media_upload),
                    actions.cancelMediaUpload,
                )
            }
        }

        state.lastDownloadedMediaName?.let { name ->
            Text(
                stringResource(R.string.media_downloaded, name),
                color = AppSuccess,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        state.lastUploadedMediaName?.let { name ->
            Text(
                stringResource(R.string.media_uploaded, name),
                color = AppSuccess,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        state.lastDeletedMediaName?.let { name ->
            Text(
                stringResource(R.string.media_deleted, name),
                color = AppSuccess,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        when {
            !state.supports(CameraFeature.MEDIA_BROWSER) -> MediaMessage(R.string.media_not_supported)
            state.mediaItems.isEmpty() && !state.isBusy(CameraOperation.MEDIA) && !state.mediaLibraryLoading -> MediaMessage(R.string.no_media)
            displayedItems.isEmpty() && !state.isBusy(CameraOperation.MEDIA) && !state.mediaLibraryLoading -> MediaMessage(R.string.no_filtered_media)
            else -> MediaGalleryGrid(
                items = displayedItems,
                sort = mediaSort,
                state = state,
                actions = actions,
                onPreview = actions.openMediaPreview,
                onActions = { activeMetadataItemId = it.id },
            )
        }
    }
}

@Composable
private fun MediaMessage(message: Int) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(message), color = AppSubtleText)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MediaMetadataSheet(
    item: CameraMediaItem,
    busy: Boolean,
    protectSupported: Boolean,
    archiveSupported: Boolean,
    ratingSupported: Boolean,
    rotationSupported: Boolean,
    downloadSupported: Boolean,
    deleteSupported: Boolean,
    onDismiss: () -> Unit,
    onProtect: (Boolean) -> Unit,
    onArchive: (Boolean) -> Unit,
    onRate: (Int) -> Unit,
    onRotate: (Int) -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AppSurface,
        contentColor = AppText,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                item.name,
                color = AppText,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AppAccent)

            if (protectSupported) {
                MetadataSectionTitle(
                    title = stringResource(R.string.media_protection),
                    value = when (item.protected) {
                        true -> stringResource(R.string.media_protected)
                        false -> stringResource(R.string.media_unprotected)
                        null -> stringResource(R.string.media_metadata_unknown)
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolIconButton(
                        LucideR.drawable.lucide_ic_lock,
                        stringResource(R.string.protect_media, item.name),
                        { onProtect(true) },
                        enabled = !busy && item.protected != true,
                        tint = if (item.protected == true) AppAccent else AppText,
                    )
                    ToolIconButton(
                        LucideR.drawable.lucide_ic_lock_open,
                        stringResource(R.string.unprotect_media, item.name),
                        { onProtect(false) },
                        enabled = !busy && item.protected != false,
                        tint = if (item.protected == false) AppAccent else AppText,
                    )
                }
            }

            if (archiveSupported) {
                MetadataSectionTitle(
                    title = stringResource(R.string.media_archive),
                    value = when (item.archived) {
                        true -> stringResource(R.string.media_archived)
                        false -> stringResource(R.string.media_not_archived)
                        null -> stringResource(R.string.media_metadata_unknown)
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolIconButton(
                        LucideR.drawable.lucide_ic_archive,
                        stringResource(R.string.archive_media, item.name),
                        { onArchive(true) },
                        enabled = !busy && item.archived != true,
                        tint = if (item.archived == true) AppAccent else AppText,
                    )
                    ToolIconButton(
                        LucideR.drawable.lucide_ic_archive_restore,
                        stringResource(R.string.unarchive_media, item.name),
                        { onArchive(false) },
                        enabled = !busy && item.archived != false,
                        tint = if (item.archived == false) AppAccent else AppText,
                    )
                }
            }

            if (ratingSupported) {
                MetadataSectionTitle(
                    title = stringResource(R.string.media_rating),
                    value = item.rating?.let { stringResource(R.string.media_rating_value, it) }
                        ?: stringResource(R.string.media_metadata_unknown),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ToolIconButton(
                        LucideR.drawable.lucide_ic_star_off,
                        stringResource(R.string.clear_media_rating, item.name),
                        { onRate(0) },
                        enabled = !busy && item.rating != 0,
                        tint = if (item.rating == 0) AppAccent else AppSubtleText,
                    )
                    (1..5).forEach { rating ->
                        ToolIconButton(
                            LucideR.drawable.lucide_ic_star,
                            stringResource(R.string.set_media_rating, item.name, rating),
                            { onRate(rating) },
                            enabled = !busy && item.rating != rating,
                            tint = if ((item.rating ?: 0) >= rating) AppWarning else AppSubtleText,
                        )
                    }
                }
            }

            if (rotationSupported) {
                MetadataSectionTitle(
                    title = stringResource(R.string.media_rotation),
                    value = item.rotationDegrees?.let { stringResource(R.string.media_rotation_value, it) }
                        ?: stringResource(R.string.media_metadata_unknown),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0, 90, 180, 270).forEach { degrees ->
                        TextButton(
                            onClick = { onRotate(degrees) },
                            enabled = !busy && item.rotationDegrees != degrees,
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) {
                            Text(
                                stringResource(R.string.rotation_degrees_short, degrees),
                                color = if (item.rotationDegrees == degrees) AppAccent else AppText,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            if (downloadSupported) {
                val downloadDescription = stringResource(R.string.download_media, item.name)
                HorizontalDivider(color = AppSurfaceHigh)
                TextButton(
                    onClick = onDownload,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(48.dp).semantics {
                        contentDescription = downloadDescription
                    },
                ) {
                    Icon(
                        painterResource(LucideR.drawable.lucide_ic_download),
                        contentDescription = null,
                        tint = AppText,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.download_media, item.name), color = AppText)
                }
            }

            if (deleteSupported) {
                HorizontalDivider(color = AppSurfaceHigh)
                TextButton(
                    onClick = onDelete,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(
                        painterResource(LucideR.drawable.lucide_ic_trash_2),
                        contentDescription = null,
                        tint = AppRecord,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.delete_media, item.name), color = AppRecord)
                }
            }
        }
    }
}

@Composable
private fun MetadataSectionTitle(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = AppText, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(value, color = AppSubtleText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatMediaSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatMediaProgress(progress: CameraMediaTransferProgress): String {
    val transferred = formatMediaSize(progress.bytesTransferred)
    val total = progress.totalBytes?.takeIf { it > 0L } ?: return transferred
    val percent = ((progress.bytesTransferred.toDouble() / total) * 100.0).coerceIn(0.0, 100.0).toInt()
    return "$transferred / ${formatMediaSize(total)} ($percent%)"
}
