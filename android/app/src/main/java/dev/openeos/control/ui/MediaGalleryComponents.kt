package dev.openeos.control.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.composables.icons.lucide.R as LucideR
import dev.openeos.control.R
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaStreamSource
import kotlinx.coroutines.CancellationException
import java.io.File

@Composable
internal fun MediaSortButton(sort: MediaSort, onSort: (MediaSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolIconButton(
            LucideR.drawable.lucide_ic_list_filter,
            stringResource(R.string.media_sort),
            { expanded = true },
            tint = AppText,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                MediaSort.NEWEST to R.string.media_newest_first,
                MediaSort.OLDEST to R.string.media_oldest_first,
                MediaSort.NAME to R.string.media_filename,
            ).forEach { (value, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(label),
                            color = if (sort == value) AppAccent else AppText,
                            fontWeight = if (sort == value) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSort(value)
                    },
                )
            }
        }
    }
}

@Composable
internal fun MediaFilterBar(
    selected: MediaFilter,
    items: List<CameraMediaItem>,
    onSelected: (MediaFilter) -> Unit,
) {
    val photos = items.count { !it.isVideo }
    val videos = items.size - photos
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            Triple(MediaFilter.ALL, R.string.media_all, items.size),
            Triple(MediaFilter.PHOTOS, R.string.media_photos, photos),
            Triple(MediaFilter.VIDEOS, R.string.media_videos, videos),
        ).forEach { (filter, label, count) ->
            TextButton(
                onClick = { onSelected(filter) },
                modifier = Modifier.weight(1f).height(44.dp),
            ) {
                Text(
                    stringResource(R.string.media_filter_count, stringResource(label), count),
                    color = if (filter == selected) AppAccent else AppSubtleText,
                    fontWeight = if (filter == selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun MediaGalleryGrid(
    items: List<CameraMediaItem>,
    sort: MediaSort,
    state: CameraUiState,
    actions: CameraActions,
    onPreview: (CameraMediaItem) -> Unit,
    onActions: (CameraMediaItem) -> Unit,
) {
    val groups = remember(items, sort) { mediaGroupsForDisplay(items, sort) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(116.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 2.dp, top = 2.dp, end = 2.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        groups.forEachIndexed { groupIndex, group ->
            item(
                key = "media-date-${group.date ?: "unknown"}-$groupIndex",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                Text(
                    text = group.date ?: stringResource(R.string.media_unknown_date),
                    color = AppSubtleText,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            items(group.items, key = CameraMediaItem::id) { item ->
                val thumbnailSupported = state.supports(CameraFeature.MEDIA_THUMBNAIL)
                LaunchedEffect(item.id, thumbnailSupported) {
                    if (thumbnailSupported) actions.loadMediaThumbnail(item)
                }
                val previewEnabled = !state.previewMode && !state.isBusy(CameraOperation.MEDIA) &&
                    if (item.isVideo) {
                        item.streamAvailable
                    } else {
                        state.supports(CameraFeature.MEDIA_PREVIEW) && item.previewAvailable
                    }
                MediaGalleryTile(
                    item = item,
                    thumbnail = state.mediaThumbnails[item.id],
                    loading = item.id in state.mediaThumbnailLoadingIds,
                    previewEnabled = previewEnabled,
                    actionsEnabled = !state.isBusy(CameraOperation.MEDIA),
                    onPreview = { onPreview(item) },
                    onActions = { onActions(item) },
                )
            }
        }
    }
}

@Composable
private fun MediaGalleryTile(
    item: CameraMediaItem,
    thumbnail: Bitmap?,
    loading: Boolean,
    previewEnabled: Boolean,
    actionsEnabled: Boolean,
    onPreview: () -> Unit,
    onActions: () -> Unit,
) {
    val previewDescription = stringResource(R.string.preview_media, item.name)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(2.dp))
            .background(AppSurfaceHigh)
            .then(
                if (previewEnabled) {
                    Modifier.semantics { contentDescription = previewDescription }.clickable(
                        role = Role.Button,
                        onClickLabel = previewDescription,
                        onClick = onPreview,
                    )
                } else Modifier
            ),
    ) {
        when {
            thumbnail != null -> Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = stringResource(R.string.media_thumbnail, item.name),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(24.dp),
                color = AppAccent,
                strokeWidth = 2.dp,
            )
            else -> Icon(
                painterResource(
                    if (item.isVideo) LucideR.drawable.lucide_ic_file_video_camera
                    else LucideR.drawable.lucide_ic_image,
                ),
                contentDescription = null,
                tint = if (item.kind.equals("raw", ignoreCase = true)) AppWarning else AppAccent,
                modifier = Modifier.align(Alignment.Center).size(34.dp),
            )
        }
        if (item.isVideo) {
            Box(
                Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.66f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(LucideR.drawable.lucide_ic_play),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (item.kind.equals("raw", ignoreCase = true)) {
            Text(
                stringResource(R.string.media_raw_badge),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.align(Alignment.BottomStart)
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            )
        }
        Box(Modifier.align(Alignment.TopEnd).padding(2.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))) {
            ToolIconButton(
                LucideR.drawable.lucide_ic_ellipsis_vertical,
                stringResource(R.string.media_actions, item.name),
                onActions,
                enabled = actionsEnabled,
                tint = Color.White,
            )
        }
    }
}

@Composable
internal fun MediaViewerDialog(
    item: CameraMediaItem,
    bytes: ByteArray?,
    streamSource: CameraMediaStreamSource?,
    loading: Boolean,
    canMovePrevious: Boolean,
    canMoveNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    downloadEnabled: Boolean = false,
    onDownload: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            when {
                item.isVideo && streamSource != null -> CameraVideoPlayer(
                    item = item,
                    source = streamSource,
                    downloadEnabled = downloadEnabled,
                    onDownload = onDownload,
                )
                !item.isVideo && bytes != null -> ZoomableMediaImage(item, bytes)
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
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(64.dp)
                    .background(Color.Black.copy(alpha = 0.76f)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolIconButton(
                    LucideR.drawable.lucide_ic_x,
                    stringResource(R.string.close_media_preview),
                    onDismiss,
                    tint = Color.White,
                )
                Text(
                    item.name,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
            }
            if (canMovePrevious) {
                ToolIconButton(
                    LucideR.drawable.lucide_ic_chevron_left,
                    stringResource(R.string.previous_media),
                    onPrevious,
                    modifier = Modifier.align(Alignment.CenterStart),
                    tint = Color.White,
                )
            }
            if (canMoveNext) {
                ToolIconButton(
                    LucideR.drawable.lucide_ic_chevron_right,
                    stringResource(R.string.next_media),
                    onNext,
                    modifier = Modifier.align(Alignment.CenterEnd),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun ZoomableMediaImage(item: CameraMediaItem, bytes: ByteArray) {
    val context = LocalContext.current
    var scale by remember(bytes) { mutableFloatStateOf(1f) }
    var offsetX by remember(bytes) { mutableFloatStateOf(0f) }
    var offsetY by remember(bytes) { mutableFloatStateOf(0f) }
    AsyncImage(
        model = ImageRequest.Builder(context).data(bytes).crossfade(false).build(),
        contentDescription = stringResource(R.string.media_preview_content, item.name),
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().padding(vertical = 64.dp)
            .pointerInput(bytes) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    if (scale == 1f) {
                        offsetX = 0f
                        offsetY = 0f
                    } else {
                        offsetX += pan.x
                        offsetY += pan.y
                    }
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
    )
}

@OptIn(markerClass = [UnstableApi::class])
@Composable
private fun CameraVideoPlayer(
    item: CameraMediaItem,
    source: CameraMediaStreamSource,
    downloadEnabled: Boolean,
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    var mode by remember(source) { mutableStateOf(CameraVideoPlaybackMode.STREAM) }
    var fallbackFile by remember(source) { mutableStateOf<File?>(null) }
    var fallbackProgress by remember(source) { mutableFloatStateOf(0f) }
    var failure by remember(source) { mutableStateOf<CameraVideoPlaybackFailure?>(null) }

    DisposableEffect(source, fallbackFile) {
        val fileToDelete = fallbackFile
        onDispose { fileToDelete?.delete() }
    }
    LaunchedEffect(mode, source) {
        if (mode != CameraVideoPlaybackMode.CACHE) return@LaunchedEffect
        try {
            val file = cacheCameraMediaForPlayback(
                source = source,
                cacheDirectory = File(context.cacheDir, "media-playback"),
            ) { transferred, total ->
                fallbackProgress = (transferred.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
            }
            fallbackFile = file
            mode = CameraVideoPlaybackMode.FILE
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            failure = if (exception.isPlaybackStorageFailure()) {
                CameraVideoPlaybackFailure.STORAGE
            } else {
                CameraVideoPlaybackFailure.TRANSFER
            }
            mode = CameraVideoPlaybackMode.FAILED
        }
    }

    Box(Modifier.fillMaxSize().padding(vertical = 64.dp)) {
        if (mode == CameraVideoPlaybackMode.STREAM || mode == CameraVideoPlaybackMode.FILE) {
            val localFile = fallbackFile.takeIf { mode == CameraVideoPlaybackMode.FILE }
            val player = remember(source, localFile) {
                ExoPlayer.Builder(context).build().apply {
                    if (localFile == null) {
                        val mediaSource = ProgressiveMediaSource.Factory(CameraMediaDataSource.Factory(source))
                            .createMediaSource(MediaItem.fromUri(Uri.parse("oec-media://camera/${item.id.hashCode()}")))
                        setMediaSource(mediaSource)
                    } else {
                        setMediaItem(MediaItem.fromUri(Uri.fromFile(localFile)))
                    }
                    playWhenReady = true
                    prepare()
                }
            }
            DisposableEffect(player, localFile) {
                val listener = object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (localFile == null) {
                            val directFailure = error.toCameraVideoPlaybackFailure()
                            if (directFailure == CameraVideoPlaybackFailure.CODEC) {
                                failure = directFailure
                                mode = CameraVideoPlaybackMode.FAILED
                            } else {
                                mode = CameraVideoPlaybackMode.CACHE
                            }
                        } else {
                            failure = error.toCameraVideoPlaybackFailure()
                            mode = CameraVideoPlaybackMode.FAILED
                        }
                    }
                }
                player.addListener(listener)
                onDispose {
                    player.removeListener(listener)
                    player.release()
                }
            }
            AndroidView(
                factory = { playerContext ->
                    PlayerView(playerContext).apply {
                        useController = true
                        controllerAutoShow = true
                        this.player = player
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (mode == CameraVideoPlaybackMode.CACHE) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    progress = { fallbackProgress },
                    color = AppAccent,
                )
                Text(
                    stringResource(R.string.media_video_preparing_local, (fallbackProgress * 100).toInt()),
                    color = AppSubtleText,
                )
            }
        }
        if (mode == CameraVideoPlaybackMode.FAILED) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(
                        when (failure) {
                            CameraVideoPlaybackFailure.CODEC -> R.string.media_video_codec_unsupported
                            CameraVideoPlaybackFailure.STORAGE -> R.string.media_video_storage_unavailable
                            else -> R.string.media_video_playback_failed
                        },
                    ),
                    color = AppSubtleText,
                )
                if (downloadEnabled) {
                    TextButton(onClick = onDownload) {
                        Text(stringResource(R.string.download_media, item.name), color = AppAccent)
                    }
                }
            }
        }
    }
}

private enum class CameraVideoPlaybackMode { STREAM, CACHE, FILE, FAILED }

private enum class CameraVideoPlaybackFailure { TRANSFER, CODEC, STORAGE }

private fun PlaybackException.toCameraVideoPlaybackFailure(): CameraVideoPlaybackFailure =
    if (
        errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
        errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
        errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
    ) {
        CameraVideoPlaybackFailure.CODEC
    } else {
        CameraVideoPlaybackFailure.TRANSFER
    }
