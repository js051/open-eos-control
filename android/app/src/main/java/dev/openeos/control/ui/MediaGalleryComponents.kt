package dev.openeos.control.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun MediaSortButton(sort: MediaSort, onSort: (MediaSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = stringResource(sort.labelResource)
    Box {
        ToolIconButton(
            LucideR.drawable.lucide_ic_list_filter,
            stringResource(R.string.media_sort_current, currentLabel),
            { expanded = true },
            tint = AppText,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                MediaSort.NEWEST to R.string.media_newest_first,
                MediaSort.OLDEST to R.string.media_oldest_first,
                MediaSort.NAME to R.string.media_filename,
                MediaSort.CAMERA to R.string.media_camera_order,
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

internal val MediaSort.labelResource: Int
    get() = when (this) {
        MediaSort.CAMERA -> R.string.media_camera_order
        MediaSort.NEWEST -> R.string.media_newest_first
        MediaSort.OLDEST -> R.string.media_oldest_first
        MediaSort.NAME -> R.string.media_filename
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
            if (sort != MediaSort.CAMERA) {
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
    position: Int,
    totalCount: Int,
    canMovePrevious: Boolean,
    canMoveNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    downloadEnabled: Boolean = false,
    onDownload: () -> Unit = {},
    actionsEnabled: Boolean = false,
    onActions: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val captureTime = mediaCaptureTimeLabel(item.captureTime)
    val size = mediaByteSizeLabel(item.sizeBytes)
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
                !item.isVideo && bytes != null -> ZoomableMediaImage(
                    item = item,
                    bytes = bytes,
                    hasBottomMetadata = captureTime != null || size != null,
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
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(76.dp)
                    .background(Color.Black.copy(alpha = 0.76f)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolIconButton(
                    LucideR.drawable.lucide_ic_x,
                    stringResource(R.string.close_media_preview),
                    onDismiss,
                    tint = Color.White,
                )
                Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(
                        item.name,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (position > 0 && totalCount > 0) {
                        Text(
                            stringResource(R.string.media_viewer_position, position, totalCount),
                            color = Color.White.copy(alpha = 0.72f),
                            maxLines = 1,
                        )
                    }
                }
                if (downloadEnabled) {
                    ToolIconButton(
                        LucideR.drawable.lucide_ic_download,
                        stringResource(R.string.download_media, item.name),
                        onDownload,
                        tint = Color.White,
                    )
                }
                if (actionsEnabled) {
                    ToolIconButton(
                        LucideR.drawable.lucide_ic_ellipsis_vertical,
                        stringResource(R.string.media_actions, item.name),
                        onActions,
                        tint = Color.White,
                    )
                }
            }
            if (canMovePrevious) {
                ViewerNavigationButton(
                    icon = LucideR.drawable.lucide_ic_chevron_left,
                    description = stringResource(R.string.previous_media),
                    onClick = onPrevious,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
            if (canMoveNext) {
                ViewerNavigationButton(
                    icon = LucideR.drawable.lucide_ic_chevron_right,
                    description = stringResource(R.string.next_media),
                    onClick = onNext,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
            if (captureTime != null || size != null) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.76f))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    captureTime?.let {
                        Text(it, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    size?.let {
                        Text(it, color = Color.White.copy(alpha = 0.72f), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableMediaImage(
    item: CameraMediaItem,
    bytes: ByteArray,
    hasBottomMetadata: Boolean,
) {
    val context = LocalContext.current
    var scale by remember(bytes) { mutableFloatStateOf(1f) }
    var offset by remember(bytes) { mutableStateOf(Offset.Zero) }
    var viewport by remember(bytes) { mutableStateOf(IntSize.Zero) }
    val imageSize = remember(bytes) { decodeMediaImageSize(bytes) }
    Box(
        Modifier.fillMaxSize().padding(vertical = 76.dp).clipToBounds().onSizeChanged { viewport = it },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(bytes).crossfade(false).build(),
            contentDescription = stringResource(R.string.media_preview_content, item.name),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
                .pointerInput(bytes, viewport, imageSize) {
                    detectTapGestures(
                        onDoubleTap = { tap ->
                            if (scale > 1.01f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = MEDIA_DOUBLE_TAP_SCALE
                                val center = Offset(viewport.width / 2f, viewport.height / 2f)
                                offset = clampMediaImageOffset(
                                    proposed = Offset(
                                        x = (center.x - tap.x) * (scale - 1f),
                                        y = (center.y - tap.y) * (scale - 1f),
                                    ),
                                    scale = scale,
                                    viewport = viewport,
                                    imageSize = imageSize,
                                )
                            }
                        },
                    )
                }
                .pointerInput(bytes, viewport, imageSize) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val oldScale = scale
                        val newScale = (oldScale * zoom).coerceIn(1f, MEDIA_MAX_SCALE)
                        if (newScale <= 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                            return@detectTransformGestures
                        }
                        val center = Offset(viewport.width / 2f, viewport.height / 2f)
                        val zoomRatio = newScale / oldScale
                        val proposed = offset + pan + (center - centroid) * (zoomRatio - 1f)
                        scale = newScale
                        offset = clampMediaImageOffset(proposed, newScale, viewport, imageSize)
                    }
                }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
        if (scale > 1.01f) {
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .padding(
                        end = 12.dp,
                        bottom = if (hasBottomMetadata) 112.dp else 12.dp,
                    )
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.68f)),
            ) {
                ToolIconButton(
                    LucideR.drawable.lucide_ic_zoom_out,
                    stringResource(R.string.reset_media_zoom),
                    {
                        scale = 1f
                        offset = Offset.Zero
                    },
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun ViewerNavigationButton(
    icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.padding(horizontal = 8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.68f)),
    ) {
        ToolIconButton(icon, description, onClick, tint = Color.White)
    }
}

private fun decodeMediaImageSize(bytes: ByteArray): IntSize {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    return if (options.outWidth > 0 && options.outHeight > 0) {
        IntSize(options.outWidth, options.outHeight)
    } else {
        IntSize.Zero
    }
}

internal fun clampMediaImageOffset(
    proposed: Offset,
    scale: Float,
    viewport: IntSize,
    imageSize: IntSize,
): Offset {
    val bounds = mediaImagePanBounds(scale, viewport, imageSize)
    return Offset(
        x = proposed.x.coerceIn(-bounds.width, bounds.width),
        y = proposed.y.coerceIn(-bounds.height, bounds.height),
    )
}

internal fun mediaImagePanBounds(scale: Float, viewport: IntSize, imageSize: IntSize): Size {
    if (scale <= 1f || viewport.width <= 0 || viewport.height <= 0 || imageSize.width <= 0 || imageSize.height <= 0) {
        return Size.Zero
    }
    val fit = min(viewport.width.toFloat() / imageSize.width, viewport.height.toFloat() / imageSize.height)
    val fittedWidth = imageSize.width * fit
    val fittedHeight = imageSize.height * fit
    return Size(
        width = max(0f, (fittedWidth * scale - viewport.width) / 2f),
        height = max(0f, (fittedHeight * scale - viewport.height) / 2f),
    )
}

private const val MEDIA_DOUBLE_TAP_SCALE = 2.5f
private const val MEDIA_MAX_SCALE = 6f

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
                            .createMediaSource(cameraVideoMediaItem(item))
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
            CameraVideoPlaybackFailureContent(
                item = item,
                failure = failure ?: CameraVideoPlaybackFailure.TRANSFER,
                downloadEnabled = downloadEnabled,
                onRetry = {
                    fallbackFile = null
                    fallbackProgress = 0f
                    failure = null
                    mode = CameraVideoPlaybackMode.STREAM
                },
                onDownload = onDownload,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

private enum class CameraVideoPlaybackMode { STREAM, CACHE, FILE, FAILED }

internal enum class CameraVideoPlaybackFailure {
    TRANSFER,
    CODEC,
    STORAGE,
    ;

    val retryable: Boolean
        get() = this != CODEC
}

@Composable
internal fun CameraVideoPlaybackFailureContent(
    item: CameraMediaItem,
    failure: CameraVideoPlaybackFailure,
    downloadEnabled: Boolean,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(
                when (failure) {
                    CameraVideoPlaybackFailure.CODEC -> R.string.media_video_codec_unsupported
                    CameraVideoPlaybackFailure.STORAGE -> R.string.media_video_storage_unavailable
                    CameraVideoPlaybackFailure.TRANSFER -> R.string.media_video_playback_failed
                },
                cameraVideoContainerLabel(item.name),
            ),
            color = AppSubtleText,
            textAlign = TextAlign.Center,
        )
        if (failure.retryable) {
            TextButton(onClick = onRetry, modifier = Modifier.height(48.dp)) {
                Icon(
                    painterResource(LucideR.drawable.lucide_ic_refresh_cw),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.retry_media_video), color = AppAccent)
            }
        }
        if (downloadEnabled) {
            TextButton(onClick = onDownload, modifier = Modifier.height(48.dp)) {
                Icon(
                    painterResource(LucideR.drawable.lucide_ic_download),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.download_original), color = AppAccent)
            }
        }
    }
}

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

internal fun cameraVideoMediaItem(item: CameraMediaItem): MediaItem {
    val extension = item.name.substringAfterLast('.', "").lowercase()
    val mimeType = when (extension) {
        "mp4", "m4v" -> MimeTypes.VIDEO_MP4
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "mkv" -> "video/x-matroska"
        else -> null
    }
    val uri = Uri.Builder()
        .scheme("oec-media")
        .authority("camera")
        .appendPath(item.id.hashCode().toString())
        .appendPath(item.name.ifBlank { "camera-video" })
        .build()
    return MediaItem.Builder()
        .setMediaId(item.id)
        .setUri(uri)
        .setMimeType(mimeType)
        .build()
}

internal fun cameraVideoContainerLabel(filename: String): String {
    val extension = filename.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "mp4" -> "MP4"
        "mov" -> "QuickTime MOV"
        "m4v" -> "M4V"
        "avi" -> "AVI"
        "mkv" -> "Matroska MKV"
        else -> extension.uppercase().ifEmpty { "VIDEO" }
    }
}
