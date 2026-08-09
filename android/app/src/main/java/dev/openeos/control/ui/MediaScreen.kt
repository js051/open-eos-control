package dev.openeos.control.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import coil.request.ImageRequest
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
        MediaPreviewDialog(
            item = item,
            bytes = state.mediaPreviewBytes,
            loading = state.mediaPreviewLoading,
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
                deleteSupported = state.supports(CameraFeature.MEDIA_DELETE),
                onDismiss = { activeMetadataItemId = null },
                onProtect = { actions.setMediaProtection(item, it) },
                onArchive = { actions.setMediaArchived(item, it) },
                onRate = { actions.setMediaRating(item, it) },
                onRotate = { actions.setMediaRotation(item, it) },
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
            ToolIconButton(
                LucideR.drawable.lucide_ic_refresh_cw,
                stringResource(R.string.refresh_media),
                actions.refreshMedia,
                enabled = !state.previewMode && !state.isBusy(CameraOperation.MEDIA),
            )
        }

        if (state.isBusy(CameraOperation.MEDIA)) {
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
            state.mediaItems.isEmpty() && !state.isBusy(CameraOperation.MEDIA) -> MediaMessage(R.string.no_media)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.mediaItems, key = { it.id }) { item ->
                    val thumbnailSupported = state.supports(CameraFeature.MEDIA_THUMBNAIL)
                    LaunchedEffect(item.id, thumbnailSupported) {
                        if (thumbnailSupported) actions.loadMediaThumbnail(item)
                    }
                    MediaRow(
                        item = item,
                        thumbnail = state.mediaThumbnails[item.id],
                        thumbnailLoading = item.id in state.mediaThumbnailLoadingIds,
                        previewEnabled = !state.previewMode &&
                            state.supports(CameraFeature.MEDIA_PREVIEW) &&
                            item.previewAvailable &&
                            !state.isBusy(CameraOperation.MEDIA),
                        deleteSupported = state.supports(CameraFeature.MEDIA_DELETE),
                        metadataSupported = state.supports(CameraFeature.MEDIA_PROTECT) ||
                            state.supports(CameraFeature.MEDIA_ARCHIVE) ||
                            (state.supports(CameraFeature.MEDIA_RATING) && item.ratingWritable != false) ||
                            state.supports(CameraFeature.MEDIA_ROTATE),
                        deleteEnabled = !state.isBusy(CameraOperation.MEDIA),
                        downloadEnabled = !state.previewMode &&
                            state.supports(CameraFeature.MEDIA_DOWNLOAD) &&
                            !state.isBusy(CameraOperation.MEDIA),
                        downloadSupported = state.supports(CameraFeature.MEDIA_DOWNLOAD),
                        onDelete = { pendingDelete = item },
                        onPreview = { actions.openMediaPreview(item) },
                        onDownload = {
                            pendingDownload = item
                            createDocument.launch(item.name)
                        },
                        onMetadata = { activeMetadataItemId = item.id },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
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
private fun MediaRow(
    item: CameraMediaItem,
    thumbnail: Bitmap?,
    thumbnailLoading: Boolean,
    previewEnabled: Boolean,
    deleteSupported: Boolean,
    metadataSupported: Boolean,
    deleteEnabled: Boolean,
    downloadSupported: Boolean,
    downloadEnabled: Boolean,
    onDelete: () -> Unit,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
    onMetadata: () -> Unit,
) {
    val previewDescription = stringResource(R.string.preview_media, item.name)
    Row(
        Modifier.fillMaxWidth().height(84.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(AppSurfaceHigh)
                .then(
                    if (previewEnabled) {
                        Modifier
                            .semantics { contentDescription = previewDescription }
                            .clickable(
                                role = Role.Button,
                                onClickLabel = previewDescription,
                                onClick = onPreview,
                            )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = stringResource(R.string.media_thumbnail, item.name),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (thumbnailLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = AppAccent,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    painterResource(
                        if (item.kind.equals("video", ignoreCase = true)) {
                            LucideR.drawable.lucide_ic_file_video_camera
                        } else {
                            LucideR.drawable.lucide_ic_image
                        },
                    ),
                    contentDescription = null,
                    tint = if (item.kind.equals("raw", ignoreCase = true)) AppWarning else AppAccent,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                color = AppText,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    item.kind.uppercase(Locale.ROOT),
                    item.sizeBytes?.let(::formatMediaSize),
                    item.captureTime?.let(::formatMediaCaptureTime),
                ).joinToString(" | "),
                color = AppSubtleText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (deleteSupported && !metadataSupported) {
            ToolIconButton(
                LucideR.drawable.lucide_ic_trash_2,
                stringResource(R.string.delete_media, item.name),
                onDelete,
                enabled = deleteEnabled,
                tint = AppRecord,
            )
        }
        if (metadataSupported) {
            ToolIconButton(
                LucideR.drawable.lucide_ic_ellipsis_vertical,
                stringResource(R.string.media_actions, item.name),
                onMetadata,
                enabled = deleteEnabled,
            )
        }
        if (downloadSupported) {
            ToolIconButton(
                LucideR.drawable.lucide_ic_download,
                stringResource(R.string.download_media, item.name),
                onDownload,
                enabled = downloadEnabled,
            )
        }
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
    deleteSupported: Boolean,
    onDismiss: () -> Unit,
    onProtect: (Boolean) -> Unit,
    onArchive: (Boolean) -> Unit,
    onRate: (Int) -> Unit,
    onRotate: (Int) -> Unit,
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

@Composable
private fun MediaPreviewDialog(
    item: CameraMediaItem,
    bytes: ByteArray?,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var decodeFailed by remember(bytes) { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            when {
                bytes != null && !decodeFailed -> AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(bytes)
                        .crossfade(false)
                        .build(),
                    contentDescription = stringResource(R.string.media_preview_content, item.name),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(vertical = 64.dp),
                    onError = { decodeFailed = true },
                )
                loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(36.dp),
                    color = AppAccent,
                    strokeWidth = 3.dp,
                )
                else -> Text(
                    stringResource(R.string.media_preview_unavailable),
                    color = AppSubtleText,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolIconButton(
                    LucideR.drawable.lucide_ic_x,
                    stringResource(R.string.close_media_preview),
                    onDismiss,
                )
                Text(
                    item.name,
                    color = AppText,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(
                    item.kind.uppercase(Locale.ROOT),
                    color = AppSubtleText,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
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

private fun formatMediaCaptureTime(value: String): String =
    if (value.length >= 16 && value[10] == 'T') {
        "${value.take(10)} ${value.substring(11, 16)}"
    } else {
        value
    }
