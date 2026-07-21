package dev.openeos.control.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import dev.openeos.control.R
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraMediaItem
import java.util.Locale

@Composable
fun MediaScreen(state: CameraUiState, actions: CameraActions) {
    var pendingDownload by remember { mutableStateOf<CameraMediaItem?>(null) }
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { destination ->
        val item = pendingDownload
        pendingDownload = null
        if (destination != null && item != null) actions.downloadMedia(item, destination)
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
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = AppAccent)
        }

        state.lastDownloadedMediaName?.let { name ->
            Text(
                stringResource(R.string.media_downloaded, name),
                color = AppSuccess,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        when {
            !state.supports(CameraFeature.MEDIA_BROWSER) -> MediaMessage(R.string.media_not_supported)
            state.mediaItems.isEmpty() && !state.isBusy(CameraOperation.MEDIA) -> MediaMessage(R.string.no_media)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.mediaItems, key = { it.id }) { item ->
                    MediaRow(
                        item = item,
                        downloadEnabled = !state.previewMode &&
                            state.supports(CameraFeature.MEDIA_DOWNLOAD) &&
                            !state.isBusy(CameraOperation.MEDIA),
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
    downloadEnabled: Boolean,
    onDownload: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
            modifier = Modifier.size(28.dp),
        )
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
        ToolIconButton(
            LucideR.drawable.lucide_ic_download,
            stringResource(R.string.download_media, item.name),
            onDownload,
            enabled = downloadEnabled,
        )
    }
}

private fun formatMediaSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatMediaCaptureTime(value: String): String =
    if (value.length >= 16 && value[10] == 'T') {
        "${value.take(10)} ${value.substring(11, 16)}"
    } else {
        value
    }
