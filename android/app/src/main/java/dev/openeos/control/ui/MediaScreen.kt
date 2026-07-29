package dev.openeos.control.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
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
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { destination ->
        val item = pendingDownload
        pendingDownload = null
        if (destination != null && item != null) actions.downloadMedia(item, destination)
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
            ToolIconButton(
                LucideR.drawable.lucide_ic_refresh_cw,
                stringResource(R.string.refresh_media),
                actions.refreshMedia,
                enabled = !state.previewMode && !state.isBusy(CameraOperation.MEDIA),
            )
        }

        if (state.isBusy(CameraOperation.MEDIA)) {
            val progress = state.mediaDownloadProgress
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

        state.lastDownloadedMediaName?.let { name ->
            Text(
                stringResource(R.string.media_downloaded, name),
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
                            (item.kind.equals("image", ignoreCase = true) ||
                                item.kind.equals("raw", ignoreCase = true)) &&
                            !state.isBusy(CameraOperation.MEDIA),
                        deleteSupported = state.supports(CameraFeature.MEDIA_DELETE),
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
    deleteEnabled: Boolean,
    downloadSupported: Boolean,
    downloadEnabled: Boolean,
    onDelete: () -> Unit,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
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
        if (deleteSupported) {
            ToolIconButton(
                LucideR.drawable.lucide_ic_trash_2,
                stringResource(R.string.delete_media, item.name),
                onDelete,
                enabled = deleteEnabled,
                tint = AppRecord,
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
